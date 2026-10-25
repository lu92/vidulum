#!/usr/bin/env python3
"""Surowy wyciąg bankowy → CSV kanoniczny (17 kolumn), bez wołania modelu.

Adapter (`bank-data-adapter`) robi to w dwóch krokach i oba są krokami AI:

1. **reguły mapowania kolumn** - model dostaje zanonimizowaną próbkę i zwraca `MappingRules`
   (które kolumny, jaki separator, jaki format daty, ile wierszy metadanych); reguły są
   cache'owane, a samo przepisanie wykonuje potem `LocalCsvTransformer` - już bez modelu;
2. **enrichment** - model dostaje partie transakcji i zwraca merchanta, klasyfikację i lokalizację,
   czyli trzy kolumny doklejane na końcu (`classification,classificationReason,location`).

Ten plik jest **zapisem obu tych zestawów decyzji dla dwóch konkretnych banków**. Powstał, bo
fixture'u kanonicznego nie da się wytworzyć bez klucza do modelu, a bez fixture'u nie da się
napisać parsera ingestion v2 (zadanie K12 w `docs/features-backlog/ingestion-v2-tasks.md`).

**Czym to NIE jest:** przebiegiem produkcyjnego pipeline'u. Kolumny `merchant`, `classification` i
`location` pochodzą tu z reguł wypisanych niżej, nie z modelu. Reguły zostały napisane po
przeczytaniu obu wyciągów (profilowanie: typy operacji, kategorie, najczęstsi kontrahenci), czyli z
tego samego materiału, który dostałby model - ale są **deterministyczne i czytelne**, co dla
fixture'u testowego jest zaletą, nie wadą: ten sam plik na wejściu daje bit w bit ten sam plik na
wyjściu, bez kosztu i bez rachunku za tokeny.

Gdy klucz będzie dostępny, `CanonicalCsvFixtureGenerator` w `vidulum-cashflow-tests` wytworzy te
same pliki prawdziwym adapterem - i wtedy warto porównać oba wyniki, bo różnica jest miarą tego, co
model wnosi ponad reguły.

Użycie:
    python3 tools/fixtures/make_canonical_csv.py
"""
import csv
import hashlib
import re
import sys
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "vidulum-cashflow-tests/src/test/resources"
OUT_DIR = ROOT / "vidulum-cashflow-tests/src/test/resources/canonical"

# Kolejność kolumn jest kontraktem tekstowym z ingestion - CsvParserService.HEADERS.
HEADERS = [
    "bankTransactionId", "name", "description", "bankCategory", "amount", "currency", "type",
    "operationDate", "bookingDate", "sourceAccountNumber", "targetAccountNumber",
    "merchant", "merchantConfidence", "paymentMethod",
    "classification", "classificationReason", "location",
]

# Rachunki właściciela wyciągów. Oba wyciągi należą do tej samej osoby, więc przelew z Nest na
# Pekao jest przelewem własnym - i to jest w tych danych 106 wierszy po stronie Nest.
OWNER_NAME = re.compile(r"lucjan\s+bik", re.I)
OWN_ACCOUNTS = {
    "93187010452083105656550001",   # Nest, z metadanych wyciągu
    "98124014441111001078171074",   # Pekao, z kolumny Rachunek źródłowy
}

# ───────────────────────────────── wspólne narzędzia

def clean(text):
    """Pionowa kreska w tych wyciągach to ślad zawijania wiersza, nie dane."""
    if text is None:
        return ""
    return re.sub(r"\s+", " ", text.replace("|", " ").replace("\\", " ")).strip()

def account(raw, currency):
    """NRB bez prefiksu → IBAN. Parser ingestion umie to sam, ale fixture ma być czytelny."""
    acc = (raw or "").strip().lstrip("'").replace(" ", "")
    if not acc:
        return ""
    if re.fullmatch(r"[A-Z]{2}\d{2}[A-Z0-9]+", acc):
        return acc
    if re.fullmatch(r"\d{26}", acc) and currency == "PLN":
        return "PL" + acc
    return acc

def money(raw):
    """-1 689,50 albo -1689.5 → (Decimal('1689.50'), 'OUTFLOW'). Skala zawsze 2."""
    txt = (raw or "").strip().replace(" ", "").replace(" ", "")
    if txt.count(",") == 1 and txt.count(".") == 0:
        txt = txt.replace(",", ".")
    else:
        txt = txt.replace(",", "")
    value = Decimal(txt)
    return abs(value).quantize(Decimal("0.01")), ("OUTFLOW" if value < 0 else "INFLOW")

def iso(raw, fmt):
    d, m, y = (raw.strip().split(fmt) + ["", "", ""])[:3]
    return f"{y}-{m.zfill(2)}-{d.zfill(2)}"

def txn_id(parts):
    """Gdy bank nie daje identyfikatora: skrót deterministyczny, bo na nim stoi idempotencja.

    **Udokumentowany w `BankCsvRow` przepis - hash(operationDate + amount + name) - na tych danych
    nie wystarcza.** Zmierzone: w wyciągu Nest 13 grup po 2-3 wiersze, razem 30 z 402 (7,5 %), ma
    identyczną datę, kwotę i nazwę, a są różnymi transakcjami - trzy osobne prowizje 10 zł tego
    samego dnia różnią się tylko saldem po operacji. Import keyowany takim id uznałby 17 z nich za
    duplikaty i cicho je pominął.

    Dlatego do skrótu wchodzi też saldo po operacji: jest w wyciągu, jest różne dla każdego wiersza
    (bo każdy zmienia stan konta) i nie psuje determinizmu - ten sam plik daje te same id."""
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:16].upper()

def payment_method(operation_type):
    """Kolejność warunków jest ta sama co w PaymentMethod.fromPolishBankType - celowo.

    Dzięki temu 'WYPŁATA KARTĄ' daje CARD (jak w Javie), a nie CASH: metoda płatności mówi JAK,
    klasyfikacja mówi CO, i te dwie odpowiedzi mają prawo się różnić.
    """
    t = (operation_type or "").upper()
    if "KART" in t or "CARD" in t:
        return "CARD"
    if "BLIK" in t:
        return "BLIK"
    if "POLECENIE ZAPŁATY" in t or "POLECENIA ZAPŁATY" in t:
        return "DIRECT_DEBIT"
    if "ZLECENIE STAŁE" in t:
        return "STANDING_ORDER"
    if any(w in t for w in ("GOTÓWK", "BANKOMAT", "ATM", "WPŁATA", "WYPŁATA")):
        return "CASH"
    if any(w in t for w in ("PRZELEW", "TRANSFER", "ELIXIR", "PEOPAY")):
        return "TRANSFER"
    return "OTHER"

CITY_SPLIT = re.compile(r"\s{2,}")
CITY_BEFORE_POSTAL = re.compile(r"([A-ZŁŚŻŹĆĄĘÓŃ][A-ZŁŚŻŹĆĄĘÓŃa-złśżźćąęóń\-]{2,})\s+\d{2}-\d{3}")
POSTAL_THEN_CITY = re.compile(r"\d{2}-\d{3}\s+([A-ZŁŚŻŹĆĄĘÓŃa-złśżźćąęóń\-]{3,})")
STORE_CODE = re.compile(r"^(?=.*\d)[A-Z0-9.\-/]+$")

# Miasta występujące w tych dwóch wyciągach jako ogon nazwy terminala. Model zna je z własnej
# wiedzy; tutaj są wypisane, bo lista z danych jest sprawdzalna, a "wiedza o świecie" nie.
CITIES = {
    "WARSZAWA", "MIELEC", "KRAKOW", "KRAKÓW", "WROCLAW", "WROCŁAW", "POZNAN", "POZNAŃ", "GDANSK",
    "GDAŃSK", "KATOWICE", "LODZ", "ŁÓDŹ", "LUBLIN", "RZESZOW", "RZESZÓW", "KIELCE", "RADOM",
    "TARNOW", "TARNÓW", "DEBICA", "DĘBICA", "GRODZISK", "PRUSZKOW", "PRUSZKÓW", "PIASECZNO",
    "DUBLIN", "LONDON", "AMSTERDAM", "BERLIN", "SAN", "VILNIUS", "LUXEMBOURG", "TALLINN",
}
# Formy prawne i szum, które nie są marką. "TYP" bierze się z nazw biletomatów.
NOISE = {
    "SPOLKA", "SPÓŁKA", "SP", "SP.", "Z", "O.O.", "OO", "ZOO", "S.A.", "SA", "AKCYJNA", "OGRAN",
    "OGRANICZONA", "ODPOWIEDZIALNOSCIA", "ODPOWIEDZIALNOŚCIĄ", "TYP", "FIRMA", "PHU", "PPHU",
    "GMBH", "LTD", "LLC", "INC", "BV", "AG", "SARL", "KG", "OY", "AB", "O", "OO.", "SUITE",
}

def merchant_and_location(counterparty, address=""):
    """Marka i miasto z nazwy kontrahenta - to jest ta część, którą normalnie zgaduje model.

    Dwa kształty w tych danych:
      'ZABKA ZC525 K.1        WARSZAWA'  → terminal kartowy: marka, kody sklepu, miasto
      'IFIRMA SA ul. Grabiszyńska 241 G 53-234 Wrocław' → firma z adresem w jednej komórce
    """
    text = clean(counterparty)
    if not text:
        return "", None, ""
    city = ""
    head = text
    # (a) terminal kartowy: marka, kody sklepu i miasto rozdzielone kilkoma spacjami
    parts = CITY_SPLIT.split(counterparty.strip())
    if len(parts) >= 2 and parts[-1].strip() and not any(c.isdigit() for c in parts[-1]):
        city = clean(parts[-1]).upper()
        head = clean(" ".join(parts[:-1]))
    # (b) miasto jako ostatni człon nazwy, bez podwójnej spacji - stąd lista CITIES
    if not city:
        tail = text.split()
        if tail and tail[-1].upper().strip(",.") in CITIES:
            city = tail[-1].upper().strip(",.")
            head = " ".join(tail[:-1])
    # (c) adres: miasto stoi przed kodem pocztowym albo po nim - sprawdzamy w tej kolejności,
    #     bo "WARSZAWA 01-797 POWĄZKOWSKA 44C" po kodzie ma ulicę, nie miasto
    if not city:
        for pattern in (CITY_BEFORE_POSTAL, POSTAL_THEN_CITY):
            found = pattern.search(text)
            if found and found.group(1).upper() not in NOISE:
                city = found.group(1).upper()
                break
    head = re.split(r"\b(?:UL\.|AL\.|UL |AL )", head, flags=re.I)[0].strip()
    head = head.split(",")[0]
    tokens = [t for t in head.split() if t]
    # Adres e-mail albo domena kończy nazwę marki: "Badoo help@badoo.com Suite 1" to BADOO,
    # a nie BADOO SUITE - wszystko po kontakcie jest już adresem.
    cut = next((i for i, t in enumerate(tokens)
                if "@" in t or re.search(r"\.(com|pl|net|org|io|ai)\b", t, re.I)), None)
    if cut is not None:
        tokens = tokens[:cut]
    # Marka to pierwsze tokeny, które nie są kodem sklepu ani formą prawną. Porównanie bez kropek,
    # bo ten sam człon pisze się "S.A.", "S.A" i "SA".
    def noise(token):
        return token.upper().replace(".", "").strip(",") in {n.replace(".", "") for n in NOISE}
    brand = [t for t in tokens if not STORE_CODE.fullmatch(t.upper()) and not noise(t)][:2]
    if len(brand) == 2 and noise(brand[1]):
        brand = brand[:1]
    if not brand:
        brand = tokens[:1]
    name = " ".join(brand).upper().strip(" .,-")
    # Pewność mówi, ile z tej nazwy jest marką, a ile adresem i kodem terminala.
    if city and len(brand) == 1:
        confidence = "0.95"
    elif city:
        confidence = "0.85"
    elif len(tokens) <= 2:
        confidence = "0.80"
    else:
        confidence = "0.60"
    return name, confidence, city

# ───────────────────────────────── enrichment: klasyfikacja

CARD_SETTLEMENT = re.compile(r"WYKONANEJ[:\s]+(.+?)(?:\s+DN\.|$)", re.I)

def classify(*, counterparty, counterparty_account, title, bank_category, operation_type, flow):
    """Jedna reguła na klasyfikację, w kolejności od najpewniejszej.

    Zwraca (classification, reason, merchant, confidence, location).
    """
    cp, title_c = clean(counterparty), clean(title)
    op, cat = (operation_type or "").upper(), (bank_category or "")
    text = f"{cp} {title_c}".upper()

    # 1. przelew własny - najpewniejszy sygnał, bo oparty na tożsamości, nie na słowach
    if OWNER_NAME.search(cp) or (counterparty_account or "").strip().lstrip("'") in OWN_ACCOUNTS \
            or cat.strip().lower() == "przelew wewnętrzny" and OWNER_NAME.search(text):
        return "SELF_TRANSFER", "counterparty is an account of the statement owner", "", None, ""

    # 2. opłaty banku - bank nie jest merchantem
    if "OPŁAT" in op.replace("OPLAT", "OPŁAT") or "PROWIZJ" in op or "PROWIZJ" in text \
            or "OPŁATA" in text or "ZALEGŁEJ OPŁATY" in op:
        return "BANK_FEE", "bank fee or commission charged by the bank itself", "", None, ""

    # 3. gotówka
    if "WYPŁATA" in op or "BANKOMAT" in text or cat.strip().lower() == "wypłata z bankomatu":
        return "CASH_WITHDRAWAL", "cash withdrawal at an ATM or terminal", "", None, ""
    if "WPŁATA" in op or "WPŁATA GOTÓWKOWA" in text:
        return "CASH_DEPOSIT", "cash deposit", "", None, ""

    # 4. odsetki
    if "ODSETKI" in text or "KAPITALIZACJA" in text:
        return "INTEREST", "interest payment or capitalisation", "", None, ""

    # 5. rozliczenie karty: nazwą jest bank, a prawdziwy merchant siedzi w tytule
    if "PEKAO S.A." in cp.upper() or "BANK PEKAO" in cp.upper():
        if "ZWROT" in title_c.upper():
            return "MERCHANT", "card transaction reversal settled by the bank", "", None, ""
        found = CARD_SETTLEMENT.search(title_c)
        if found:
            # Tylko tytuł, nigdy nazwa ani adres kontrahenta: kontrahentem jest tu bank, więc jego
            # adres podstawiłby "WARSZAWA" pod transakcję zrobioną w Dublinie.
            name, conf, city = merchant_and_location(found.group(1))
            return ("MERCHANT",
                    "merchant extracted from a card settlement title (payer shown as the bank)",
                    name, conf or "0.70", city)
        return "UNKNOWN", "bank-intermediary row with no merchant in the title", "", None, ""

    # 6. w przeciwnym razie: zapłata komuś - firmie, osobie albo urzędowi
    name, conf, city = merchant_and_location(counterparty)
    if not name:
        return "UNKNOWN", "neither counterparty nor title identifies a party", "", None, ""
    reason = ("government or public institution" if re.search(
        r"\b(urz[ąa]d|zus|skarbow|gmina|miasto|parafia)\b", text, re.I)
        else f"{'incoming' if flow == 'INFLOW' else 'outgoing'} payment to an identified party")
    return "MERCHANT", reason, name, conf, city

# ───────────────────────────────── profile banków (to, co model zwraca jako MappingRules)

def transform_nest(path):
    rows = list(csv.reader(path.open(encoding="utf-8")))
    header_row = 6                      # sześć wierszy metadanych: rachunek, właściciel, okres, liczba, sumy
    data = [r for r in rows[header_row + 1:] if any(c.strip() for c in r)]
    out = []
    for r in data:
        booking, operation, op_type, amount_raw, currency = r[0], r[1], r[2], r[3], r[4]
        counterparty, cp_account, title = r[5], r[6], r[7]
        amount, flow = money(amount_raw)
        cls, reason, merchant, conf, location = classify(
            counterparty=counterparty, counterparty_account=cp_account, title=title,
            bank_category=op_type, operation_type=op_type, flow=flow)
        name = clean(counterparty) or clean(title)
        own = account("93187010452083105656550001", currency)
        other = account(cp_account, currency)
        out.append({
            "bankTransactionId": txn_id(
                [iso(operation, "-"), str(amount), currency, name, r[8].strip()]),   # r[8] = saldo po operacji
            "name": name,
            "description": clean(title),
            "bankCategory": clean(op_type),
            "amount": f"{amount}",
            "currency": currency.strip(),
            "type": flow,
            "operationDate": iso(operation, "-"),
            "bookingDate": iso(booking, "-"),
            "sourceAccountNumber": own if flow == "OUTFLOW" else other,
            "targetAccountNumber": other if flow == "OUTFLOW" else own,
            "merchant": merchant,
            "merchantConfidence": conf or "",
            "paymentMethod": payment_method(op_type),
            "classification": cls,
            "classificationReason": reason,
            "location": location,
        })
    return out

def transform_pekao(path):
    rows = list(csv.reader(path.open(encoding="utf-8"), delimiter=";"))
    data = [r for r in rows[1:] if any(c.strip() for c in r)]
    out = []
    for r in data:
        booking, value_date, counterparty, address = r[0], r[1], r[2], r[3]
        src, dst, title, amount_raw, currency, reference, op_type, category = r[4:12]
        amount, flow = money(amount_raw)
        cls, reason, merchant, conf, location = classify(
            counterparty=counterparty, counterparty_account=(dst if flow == "OUTFLOW" else src),
            title=title, bank_category=category, operation_type=op_type, flow=flow)
        if not location and cls != "MERCHANT" or (not location and merchant):
            _, _, location = merchant_and_location(counterparty, address)
        # Siedem wierszy opłat Pekao nie ma kontrahenta - bank nie jest kontrahentem sam dla
        # siebie. `name` jest w kontrakcie kanonicznym WYMAGANE, więc spada na tytuł, a gdyby i
        # tytuł był pusty - na kategorię banku.
        name = clean(counterparty) or clean(title) or clean(category)
        out.append({
            "bankTransactionId": reference.strip().lstrip("'"),
            "name": name,
            "description": clean(title),
            "bankCategory": clean(category),
            "amount": f"{amount}",
            "currency": currency.strip(),
            "type": flow,
            "operationDate": iso(value_date, "."),
            "bookingDate": iso(booking, "."),
            "sourceAccountNumber": account(src, currency),
            "targetAccountNumber": account(dst, currency),
            "merchant": merchant,
            "merchantConfidence": conf or "",
            "paymentMethod": payment_method(op_type),
            "classification": cls,
            "classificationReason": reason,
            "location": location,
        })
    return out

BANKS = {
    "nestbank": ("nestbank_lista_operacji_20260111.csv", "nestbank-canonical.csv", transform_nest),
    "pekao": ("pekao_sa_Lista_operacji_20260111_013400.csv", "pekao-canonical.csv", transform_pekao),
}

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for bank, (raw_name, out_name, fn) in BANKS.items():
        raw = RAW_DIR / raw_name
        if not raw.exists():
            sys.exit(f"brak pliku surowego: {raw}")
        rows = fn(raw)
        target = OUT_DIR / out_name
        with target.open("w", encoding="utf-8", newline="\n") as fh:
            writer = csv.DictWriter(fh, fieldnames=HEADERS, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
        print(f"{bank}: {len(rows)} wierszy → {target.relative_to(ROOT)}")

if __name__ == "__main__":
    main()

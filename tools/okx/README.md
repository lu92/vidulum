# tools/okx

Dwa samodzielne skrypty (ESM, Node >= 22, **zero zależności npm**) do read-only wglądu w konto OKX:

| Plik | Co robi |
|------|---------|
| `okx-readonly-export-2.mjs` | Eksport REST: uid/uprawnienia klucza, salda Trading + Funding, wpłaty, wypłaty, fills, bills |
| `okx-ws-listener-3.mjs` | Nasłuch prywatnego WebSocketu (`orders`, `balance_and_position`, `account`, opcjonalnie `fills`) |

## Przygotowanie `.env`

```bash
cp .env.example .env.prod   # klucz live  -> OKX_*
cp .env.example .env.demo   # klucz Demo  -> OKX_DEMO_*
```

Uzupełnij `KEY` / `SECRET` / `PASSPHRASE` w każdym pliku. Konto EEA (`my.okx.com`) wymaga
`OKX_DOMAIN=eea.okx.com` — bez tego REST idzie na `openapi.okx.com` i zwraca `60032`.
Klucze rób **read-only**; skrypt eksportu ostrzega, jeśli klucz ma szersze uprawnienia.
`.env.prod` i `.env.demo` są w `.gitignore`, `.env.example` nie.

## Uruchamianie

```bash
npm run check:demo         # smoke test poświadczeń: --verbose, okno od wczoraj -> check-demo.json
npm run check:prod         # to samo na kluczu live -> check-prod.json

npm run export:demo        # pełny eksport (90 dni wstecz) -> demo.json
npm run export:prod        #                              -> prod.json
npm run export:demo:quick  # jw. z --skip-bills (szybciej, bez dziennika konta)
npm run export:prod:quick

npm run ws:demo            # WS demo, region eea (wseeapap.okx.com)
npm run ws:prod            # WS live, region eea (wseea.okx.com)
```

Każdy skrypt npm ładuje właściwy plik przez `node --env-file=`. Argumenty można dokładać po `--`:

```bash
npm run export:prod -- --from 2026-01-01 --to 2026-06-30 --out h1.json
npm run ws:prod -- --channels orders,fills,deposit-info
```

Pełna lista flag jest w nagłówkowym komentarzu każdego `.mjs`.

## Ograniczenia i pułapki

- **`fills-history` sięga 3 miesiące wstecz.** Starsze `--from` nie zwróci transakcji — po prostu
  ich tam nie ma. To samo dotyczy `bills-archive` (3 mies.); `asset/bills` (Funding) to tylko 1 miesiąc.
- **Kanał WS `fills` wymaga VIP5+.** Żyje na endpoincie `/ws/v5/business`, a subskrypcja na niższym
  poziomie zostanie odrzucona. Domyślne kanały (`orders`, `balance_and_position`, `account`) działają
  na każdym koncie.
- **`orders` to kanał powiadomień, nie źródło prawdy.** Dostajesz zmiany stanu zlecenia
  (`state=filled` itd.); pełną historię wykonań i tak trzeba dobrać przez REST (`fills-history`).
- **`60032` (REST) / `50119` (WS login) = zły region.** Klucz z `my.okx.com` działa wyłącznie na
  `eea.okx.com` + `wseea.okx.com` / `wseeapap.okx.com` (demo). Ten sam klucz na domenie globalnej
  wygląda jak „nieistniejący API key".
- **Passphrase ze znakiem `#` musi być w cudzysłowach** w pliku `.env` (`OKX_PASSPHRASE='moja#fraza'`). `node --env-file` obcina niecytowaną wartość na `#`, a OKX zwraca wtedy `50105 OK-ACCESS-PASSPHRASE incorrect`. Dotyczy też spacji w wartości.
- Profil `demo` automatycznie dokłada nagłówek `x-simulated-trading: 1`; kluczy demo i live nie da
  się mieszać między profilami.
- Pliki wynikowe (`*.json`) są ignorowane przez git — `package.json` jest wyjątkiem.

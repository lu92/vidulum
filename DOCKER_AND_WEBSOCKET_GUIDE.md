# Docker & WebSocket Gateway - Kompletny Przewodnik

## Spis treści
1. [Budowanie obrazów Docker](#budowanie-obrazów-docker)
2. [Zarządzanie kontenerami](#zarządzanie-kontenerami)
3. [Testowanie integracji](#testowanie-integracji)
4. [User Journey - krok po kroku](#user-journey---krok-po-kroku)
5. [Debugowanie WebSocket lokalnie](#debugowanie-websocket-lokalnie)
6. [Security](#security)
7. [Znane problemy i rozwiązania](#znane-problemy-i-rozwiązania)

---

## Budowanie obrazów Docker

### Wymagania
- Docker Desktop
- Java 21
- Maven (lub użyj `./mvnw`)

### Budowanie obrazu Vidulum (główna aplikacja)

```bash
# Z katalogu głównego projektu
cd /Users/lucjanbik/IdeaProjects/vidulum

# Kompilacja i pakowanie
./mvnw clean package -DskipTests

# Budowanie obrazu Docker
docker build -t vidulum:latest .
```

### Budowanie obrazu WebSocket Gateway

```bash
# Z katalogu websocket-gateway
cd /Users/lucjanbik/IdeaProjects/vidulum/websocket-gateway

# Kompilacja i pakowanie
./mvnw clean package -DskipTests

# Budowanie obrazu Docker
docker build -t vidulum-websocket-gateway:latest .
```

### Weryfikacja obrazów

```bash
# Lista obrazów
docker images | grep vidulum

# Oczekiwany output:
# vidulum                      latest    abc123...   1 minute ago    450MB
# vidulum-websocket-gateway    latest    def456...   1 minute ago    380MB
```

---

## Zarządzanie kontenerami

### Uruchamianie pełnego stacka

```bash
# Z katalogu głównego projektu
cd /Users/lucjanbik/IdeaProjects/vidulum

# Uruchomienie wszystkich serwisów
docker-compose -f docker-compose-final.yml up -d

# Sprawdzenie statusu
docker-compose -f docker-compose-final.yml ps
```

### Serwisy w docker-compose-final.yml

| Serwis | Port | Opis |
|--------|------|------|
| mongodb | 27017 | Baza danych |
| zookeeper | 2181 | Koordynator Kafka |
| kafka | 9092 | Message broker |
| kafdrop | 9000 | UI do podglądu Kafki |
| vidulum-app | 9090 | Główna aplikacja API |
| websocket-gateway | 8081 | WebSocket Gateway |

### Podstawowe komendy

```bash
# Zatrzymanie wszystkich kontenerów
docker-compose -f docker-compose-final.yml down

# Restart pojedynczego serwisu
docker-compose -f docker-compose-final.yml restart websocket-gateway

# Logi serwisu
docker logs -f websocket-gateway
docker logs -f vidulum-app

# Przebudowanie i restart po zmianach kodu
docker-compose -f docker-compose-final.yml stop websocket-gateway
docker-compose -f docker-compose-final.yml rm -f websocket-gateway
# ... zbuduj nowy obraz ...
docker-compose -f docker-compose-final.yml up -d websocket-gateway
```

### Czyszczenie

```bash
# Usuń kontenery i sieci
docker-compose -f docker-compose-final.yml down

# Usuń też wolumeny (UWAGA: kasuje dane!)
docker-compose -f docker-compose-final.yml down -v

# Usuń nieużywane obrazy
docker image prune -f
```

---

## Testowanie integracji

### Krok 1: Uzyskanie tokenu JWT

```bash
# Rejestracja użytkownika
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "password123"
  }'

# Logowanie - otrzymasz token
curl -X POST http://localhost:9090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'

# Response zawiera token:
# {"token": "eyJhbGciOiJIUzI1NiJ9...", "refreshToken": "..."}
```

### Krok 2: Zapisz token do zmiennej

```bash
export TOKEN="eyJhbGciOiJIUzI1NiJ9..."
```

### Krok 3: Test połączenia WebSocket

```bash
# Instalacja websocat (jeśli nie masz)
brew install websocat

# Połączenie z WebSocket Gateway
websocat "ws://localhost:8081/ws?token=$TOKEN"
```

### Krok 4: Subskrypcja na temat

Po połączeniu, wpisz w terminalu websocat:

```json
{"action": "subscribe", "topic": "cash_flow", "cashFlowId": "YOUR_CASH_FLOW_ID"}
```

### Krok 5: Wygenerowanie eventu (w osobnym terminalu)

```bash
# Utworzenie CashFlow z historią
curl -X POST http://localhost:9090/cash-flow/with-history \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "userId": "testuser",
    "name": "Test Account",
    "description": "Test",
    "bankAccount": {"currency": "PLN"},
    "startPeriod": "2025-10",
    "initialBalance": {"amount": 5000.00, "currency": "PLN"}
  }'
```

W terminalu websocat zobaczysz event:

```json
{
  "type": "event",
  "topic": "cash_flow",
  "eventType": "CashFlowWithHistoryCreatedEvent",
  "cashFlowId": "...",
  "data": {...}
}
```

---

## User Journey - krok po kroku

### Scenariusz: Konfiguracja nowego konta bankowego z historią

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         USER JOURNEY: Setup Mode                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. CONNECT TO WEBSOCKET                                                    │
│     ─────────────────────                                                   │
│     ws://localhost:8081/ws?token=JWT                                        │
│                                                                             │
│     Response: Connection established                                        │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  2. CREATE CASHFLOW WITH HISTORY                                            │
│     ───────────────────────────────                                         │
│     POST /cash-flow/with-history                                            │
│     {                                                                       │
│       "userId": "testuser",                                                 │
│       "name": "Main Bank Account",                                          │
│       "startPeriod": "2025-10",        // 3 miesiące wstecz                 │
│       "initialBalance": {"amount": 5000, "currency": "PLN"}                 │
│     }                                                                       │
│                                                                             │
│     → CashFlow status: SETUP                                                │
│     → WebSocket event: CashFlowWithHistoryCreatedEvent                      │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  3. SUBSCRIBE TO EVENTS                                                     │
│     ──────────────────────                                                  │
│     WebSocket send:                                                         │
│     {"action": "subscribe", "topic": "cash_flow", "cashFlowId": "..."}      │
│                                                                             │
│     Response: {"type": "ack", "success": true}                              │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  4. IMPORT HISTORICAL TRANSACTIONS                                          │
│     ─────────────────────────────────                                       │
│     POST /cash-flow/{id}/import-historical                                  │
│     {                                                                       │
│       "category": "Utilities",                                              │
│       "name": "Electric bill",                                              │
│       "money": {"amount": -150, "currency": "PLN"},                         │
│       "type": "OUTFLOW",                                                    │
│       "paidDate": "2025-11-15T10:00:00Z"   // musi być < activePeriod       │
│     }                                                                       │
│                                                                             │
│     → WebSocket event: HistoricalCashChangeImportedEvent                    │
│                                                                             │
│     Powtórz dla każdej historycznej transakcji...                           │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  5. ATTEST HISTORICAL IMPORT                                                │
│     ───────────────────────────                                             │
│     POST /cash-flow/{id}/attest-historical-import                           │
│     {                                                                       │
│       "confirmedBalance": {"amount": 5150, "currency": "PLN"},              │
│       "forceAttestation": false,                                            │
│       "createAdjustment": false                                             │
│     }                                                                       │
│                                                                             │
│     → CashFlow status: SETUP → OPEN                                         │
│     → WebSocket event: HistoricalImportAttestedEvent                        │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  6. ADD CURRENT TRANSACTIONS (normal mode)                                  │
│     ─────────────────────────────────────────                               │
│     POST /cash-flow/paid-cash-change                                        │
│     {                                                                       │
│       "cashFlowId": "...",                                                  │
│       "category": "Food",                                                   │
│       "name": "Groceries",                                                  │
│       "money": {"amount": -75.50, "currency": "PLN"},                       │
│       "type": "OUTFLOW",                                                    │
│       "paidDate": "2026-01-09T10:00:00Z"                                    │
│     }                                                                       │
│                                                                             │
│     → WebSocket event: PaidCashChangeAppendedEvent                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Diagram przepływu eventów

```
┌──────────┐     HTTP      ┌──────────┐     Kafka      ┌───────────┐    WebSocket   ┌──────────┐
│  Client  │ ───────────▶  │ Vidulum  │ ────────────▶  │  Gateway  │ ─────────────▶ │  Client  │
│  (curl)  │               │   API    │                │           │                │(websocat)│
└──────────┘               └──────────┘                └───────────┘                └──────────┘
     │                          │                            │                           │
     │  POST /cash-flow/...     │                            │                           │
     │ ─────────────────────▶   │                            │                           │
     │                          │                            │                           │
     │                          │  CashFlowEvent             │                           │
     │                          │  to topic: cash_flow       │                           │
     │                          │ ─────────────────────────▶ │                           │
     │                          │                            │                           │
     │                          │                            │  event: {...}             │
     │                          │                            │ ─────────────────────────▶│
     │                          │                            │                           │
     │  HTTP 200 OK             │                            │                           │
     │ ◀─────────────────────── │                            │                           │
     │                          │                            │                           │
```

---

## Debugowanie WebSocket lokalnie

### Metoda 1: websocat (CLI)

```bash
# Instalacja
brew install websocat

# Połączenie
websocat "ws://localhost:8081/ws?token=$TOKEN"

# Komendy do wpisania:
{"action": "ping"}
{"action": "subscribe", "topic": "cash_flow", "cashFlowId": "abc-123"}
{"action": "unsubscribe", "topic": "cash_flow", "cashFlowId": "abc-123"}
```

### Metoda 2: Python script

Zapisz jako `ws_debug.py`:

```python
#!/usr/bin/env python3
import asyncio
import json
import websockets

TOKEN = "YOUR_JWT_TOKEN_HERE"
WS_URL = f"ws://localhost:8081/ws?token={TOKEN}"

async def listen():
    async with websockets.connect(WS_URL) as ws:
        print("Connected!")

        # Subscribe
        await ws.send(json.dumps({
            "action": "subscribe",
            "topic": "cash_flow",
            "cashFlowId": "YOUR_CASHFLOW_ID"
        }))

        # Listen for events
        while True:
            msg = await ws.recv()
            print(f"\n>>> RECEIVED:\n{json.dumps(json.loads(msg), indent=2)}")

asyncio.run(listen())
```

```bash
pip3 install websockets
python3 ws_debug.py
```

### Metoda 3: Browser DevTools

```javascript
// Otwórz DevTools (F12) → Console

const token = "YOUR_JWT_TOKEN";
const ws = new WebSocket(`ws://localhost:8081/ws?token=${token}`);

ws.onopen = () => {
    console.log("Connected!");
    ws.send(JSON.stringify({
        action: "subscribe",
        topic: "cash_flow",
        cashFlowId: "YOUR_CASHFLOW_ID"
    }));
};

ws.onmessage = (event) => {
    console.log("Received:", JSON.parse(event.data));
};

ws.onerror = (error) => console.error("Error:", error);
ws.onclose = () => console.log("Disconnected");

// Ping test
ws.send(JSON.stringify({action: "ping"}));
```

### Metoda 4: Kafdrop UI (podgląd Kafki)

```
http://localhost:9000
```

- Zobacz tematy: `cash_flow`, `bank_data_ingestion`
- Podgląd wiadomości w czasie rzeczywistym
- Sprawdź offsety consumer group `websocket-gateway`

### Metoda 5: Docker logs

```bash
# Logi gateway w czasie rzeczywistym
docker logs -f websocket-gateway

# Filtrowanie eventów
docker logs websocket-gateway 2>&1 | grep "event"

# Sprawdzenie RAW eventów z Kafki
docker logs websocket-gateway 2>&1 | grep "RAW"
```

---

## Security

### JWT Token

**Konfiguracja:**
- Secret przechowywany jako BASE64 encoded string
- Algorytm: HS256
- Lokalizacja: `gateway.jwt.secret` w `application.yml`

**WAŻNE:** Secret musi być identyczny w vidulum i websocket-gateway!

```yaml
# websocket-gateway/src/main/resources/application.yml
gateway:
  jwt:
    secret: ${JWT_SECRET:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}
```

```yaml
# vidulum application.yml
security:
  jwt:
    secret-key: ${JWT_SECRET:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}
```

### WebSocket Authentication

1. Token przekazywany jako query parameter: `?token=JWT`
2. Walidacja przy handshake w `WebSocketAuthInterceptor`
3. UserId ekstrahowany z JWT i zapisywany w sesji
4. Brak tokenu lub nieprawidłowy token = odrzucenie połączenia

### CORS

```yaml
gateway:
  websocket:
    allowed-origins: "*"  # W produkcji zmień na konkretne domeny!
```

### Kafka Security

Obecnie Kafka działa bez autentykacji (development mode). W produkcji:
- Włącz SASL/SSL
- Skonfiguruj ACL
- Użyj osobnych credentials dla każdego serwisu

### Checklist produkcyjny

- [ ] Zmień JWT secret na unikalny, długi klucz
- [ ] Ogranicz CORS do konkretnych domen
- [ ] Włącz TLS/SSL dla WebSocket (wss://)
- [ ] Skonfiguruj Kafka security
- [ ] Użyj secrets management (Vault, K8s secrets)
- [ ] Włącz rate limiting
- [ ] Skonfiguruj monitoring i alerting

---

## Znane problemy i rozwiązania

### Problem 1: JWT signature mismatch

**Symptom:**
```
WebSocket connection rejected
Logs: "Failed to parse JWT: JWT signature does not match"
```

**Przyczyna:** Różne dekodowanie secretu w vidulum vs gateway.

**Rozwiązanie:** Upewnij się, że oba serwisy używają BASE64 decoding:
```java
// JwtService.java
byte[] keyBytes = Decoders.BASE64.decode(secret);
this.secretKey = Keys.hmacShaKeyFor(keyBytes);
```

### Problem 2: Events type=null, cashFlowId=null

**Symptom:**
```
Received cash_flow event: type=null, cashFlowId=null
```

**Przyczyna:** Vidulum używa `metadata.event`, gateway szukał `metadata.eventType`.

**Rozwiązanie:** W `KafkaEvent.java`:
```java
public String getEventType() {
    if (metadata == null) return null;
    String eventType = (String) metadata.get("eventType");
    if (eventType == null) {
        eventType = (String) metadata.get("event");
    }
    return eventType;
}
```

### Problem 3: Docker nie używa nowego obrazu

**Symptom:** Po przebudowaniu obrazu, kontener nadal używa starego kodu.

**Rozwiązanie:**
```bash
# Usuń kontener całkowicie
docker-compose -f docker-compose-final.yml stop websocket-gateway
docker-compose -f docker-compose-final.yml rm -f websocket-gateway

# Przebuduj obraz
docker build -t vidulum-websocket-gateway:latest .

# Uruchom na nowo
docker-compose -f docker-compose-final.yml up -d websocket-gateway
```

### Problem 4: Kafka consumer nie odbiera eventów

**Symptom:** Eventy są w Kafce (widoczne w Kafdrop), ale gateway ich nie przetwarza.

**Rozwiązanie:**
1. Sprawdź consumer group offset w Kafdrop
2. Zresetuj offset jeśli potrzeba:
```bash
docker exec -it kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group websocket-gateway \
  --reset-offsets --to-earliest \
  --topic cash_flow \
  --execute
```

### Problem 5: Token expired

**Symptom:**
```
WebSocket connection rejected
Logs: "Token expired"
```

**Rozwiązanie:**
1. Zaloguj się ponownie i uzyskaj nowy token
2. Użyj refresh token endpoint jeśli dostępny
3. W development: wygeneruj token z dłuższym czasem życia

### Problem 6: WebSocket disconnects randomly

**Symptom:** Połączenie WebSocket zrywa się po pewnym czasie.

**Rozwiązanie:**
1. Implementuj ping/pong heartbeat (co 30s)
2. Sprawdź timeout w load balancerze/proxy
3. Dodaj reconnection logic w kliencie:

```javascript
function connect() {
    const ws = new WebSocket(url);
    ws.onclose = () => {
        console.log("Disconnected, reconnecting in 5s...");
        setTimeout(connect, 5000);
    };
}
```

---

## Przydatne komendy - ściągawka

```bash
# === BUDOWANIE ===
./mvnw clean package -DskipTests                    # Pakowanie
docker build -t vidulum:latest .                    # Obraz vidulum
docker build -t vidulum-websocket-gateway:latest .  # Obraz gateway

# === URUCHAMIANIE ===
docker-compose -f docker-compose-final.yml up -d    # Start wszystko
docker-compose -f docker-compose-final.yml ps       # Status
docker-compose -f docker-compose-final.yml down     # Stop wszystko

# === LOGI ===
docker logs -f websocket-gateway                    # Logi gateway
docker logs -f vidulum-app                          # Logi vidulum
docker logs websocket-gateway 2>&1 | grep "event"   # Filtruj eventy

# === DEBUG ===
websocat "ws://localhost:8081/ws?token=$TOKEN"      # WebSocket CLI
open http://localhost:9000                          # Kafdrop UI

# === RESTART PO ZMIANACH ===
docker-compose -f docker-compose-final.yml stop websocket-gateway && \
docker-compose -f docker-compose-final.yml rm -f websocket-gateway && \
cd websocket-gateway && ./mvnw clean package -DskipTests && \
docker build -t vidulum-websocket-gateway:latest . && \
cd .. && docker-compose -f docker-compose-final.yml up -d websocket-gateway
```

---

## Pełny test integracyjny - skrypt Python

Zapisz jako `/tmp/test_integration.py`:

```python
#!/usr/bin/env python3
"""
Integration test for Vidulum + WebSocket Gateway
Tests: WebSocket connection, CashFlow with history, import, attestation
"""

import asyncio
import json
import requests
import websockets
from datetime import datetime, timedelta, timezone

# Configuration
API_BASE = "http://localhost:9090"
WS_URL = "ws://localhost:8081/ws"
TOKEN = "YOUR_JWT_TOKEN_HERE"
USER_ID = "testuser"

HEADERS = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {TOKEN}"
}

def print_http(method, url, request_body, response):
    print(f"\n{'='*60}")
    print(f"HTTP {method} {url}")
    print(f"{'='*60}")
    if request_body:
        print(f">>> REQUEST BODY:")
        print(json.dumps(request_body, indent=2))
    print(f"\n<<< RESPONSE [{response.status_code}]:")
    try:
        print(json.dumps(response.json(), indent=2))
    except:
        print(response.text[:500])

def print_ws(direction, message):
    print(f"\n{'*'*60}")
    print(f"WEBSOCKET {direction}")
    print(f"{'*'*60}")
    if isinstance(message, str):
        try:
            print(json.dumps(json.loads(message), indent=2))
        except:
            print(message)
    else:
        print(json.dumps(message, indent=2))

async def main():
    print("\n" + "="*70)
    print("INTEGRATION TEST: Vidulum + WebSocket Gateway")
    print("="*70)

    # 1. Connect to WebSocket
    print("\n### STEP 1: Connect to WebSocket Gateway ###")
    ws_url_with_token = f"{WS_URL}?token={TOKEN}"
    print(f"Connecting to: {WS_URL}?token=<JWT>")

    try:
        ws = await websockets.connect(ws_url_with_token)
        print("WebSocket connection established!")
    except Exception as e:
        print(f"Failed to connect: {e}")
        return

    # 2. Create CashFlow with history
    print("\n### STEP 2: Create CashFlow with History ###")
    today = datetime.now()
    start_period = (today - timedelta(days=90)).replace(day=1)

    create_cf_body = {
        "userId": USER_ID,
        "name": "Test Bank Account",
        "description": "Integration test account",
        "bankAccount": {"currency": "PLN"},
        "startPeriod": start_period.strftime("%Y-%m"),
        "initialBalance": {"amount": 5000.00, "currency": "PLN"}
    }

    response = requests.post(f"{API_BASE}/cash-flow/with-history", headers=HEADERS, json=create_cf_body)
    print_http("POST", "/cash-flow/with-history", create_cf_body, response)

    if response.status_code not in [200, 201]:
        print("Failed to create CashFlow!")
        await ws.close()
        return

    cash_flow_id = response.text.strip().strip('"')
    print(f"\nCreated CashFlow ID: {cash_flow_id}")

    # 3. Subscribe to WebSocket topics
    print("\n### STEP 3: Subscribe to WebSocket topics ###")

    subscribe_msg = {"action": "subscribe", "topic": "cash_flow", "cashFlowId": cash_flow_id}
    await ws.send(json.dumps(subscribe_msg))
    print_ws(">>> SENT", subscribe_msg)

    ack = await ws.recv()
    print_ws("<<< RECEIVED", ack)

    # 4. Import historical transaction
    print("\n### STEP 4: Import Historical Transaction ###")
    import_date = today - timedelta(days=45)
    import_body = {
        "category": "Utilities",
        "name": "Historical electric bill",
        "description": "Electric bill payment",
        "money": {"amount": -150.00, "currency": "PLN"},
        "type": "OUTFLOW",
        "dueDate": import_date.isoformat() + "Z",
        "paidDate": import_date.isoformat() + "Z"
    }

    response = requests.post(f"{API_BASE}/cash-flow/{cash_flow_id}/import-historical", headers=HEADERS, json=import_body)
    print_http("POST", f"/cash-flow/{cash_flow_id}/import-historical", import_body, response)

    # Listen for events
    print("\n### Checking for WebSocket events... ###")
    await asyncio.sleep(2)
    try:
        while True:
            message = await asyncio.wait_for(ws.recv(), timeout=1.0)
            print_ws("<<< RECEIVED (Event)", message)
    except asyncio.TimeoutError:
        print("No more WebSocket events")

    # 5. Attestation
    print("\n### STEP 5: Create Historical Import Attestation ###")
    attestation_body = {
        "confirmedBalance": {"amount": 5150.00, "currency": "PLN"},
        "forceAttestation": False,
        "createAdjustment": False
    }

    response = requests.post(f"{API_BASE}/cash-flow/{cash_flow_id}/attest-historical-import", headers=HEADERS, json=attestation_body)
    print_http("POST", f"/cash-flow/{cash_flow_id}/attest-historical-import", attestation_body, response)

    await asyncio.sleep(2)
    try:
        while True:
            message = await asyncio.wait_for(ws.recv(), timeout=1.0)
            print_ws("<<< RECEIVED (Event)", message)
    except asyncio.TimeoutError:
        print("No more WebSocket events")

    # 6. Add current transaction
    print("\n### STEP 6: Add Paid CashChange ###")
    now_utc = datetime.now(timezone.utc) - timedelta(minutes=5)
    cashchange_body = {
        "cashFlowId": cash_flow_id,
        "category": "Food",
        "name": "Grocery shopping",
        "description": "Weekly groceries",
        "money": {"amount": -75.50, "currency": "PLN"},
        "type": "OUTFLOW",
        "dueDate": now_utc.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z",
        "paidDate": now_utc.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    }

    response = requests.post(f"{API_BASE}/cash-flow/paid-cash-change", headers=HEADERS, json=cashchange_body)
    print_http("POST", "/cash-flow/paid-cash-change", cashchange_body, response)

    await asyncio.sleep(2)
    try:
        while True:
            message = await asyncio.wait_for(ws.recv(), timeout=1.0)
            print_ws("<<< RECEIVED (Event)", message)
    except asyncio.TimeoutError:
        print("No more WebSocket events")

    # 7. Ping/Pong test
    print("\n### STEP 7: Ping/Pong Test ###")
    ping_msg = {"action": "ping"}
    await ws.send(json.dumps(ping_msg))
    print_ws(">>> SENT", ping_msg)
    pong = await ws.recv()
    print_ws("<<< RECEIVED", pong)

    await ws.close()
    print("\n" + "="*70)
    print("TEST COMPLETED!")
    print("="*70)

if __name__ == "__main__":
    asyncio.run(main())
```

Uruchomienie:
```bash
pip3 install websockets requests
python3 /tmp/test_integration.py
```

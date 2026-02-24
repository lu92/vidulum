# Performance Testing Guide

## Narzędzia do testów obciążeniowych

### 1. k6 (polecane - nowoczesne, proste)

```bash
# Instalacja
brew install k6

# Przykładowy test
k6 run --vus 100 --duration 30s script.js
```

Przykładowy skrypt testowy:

```javascript
// k6-load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
  stages: [
    { duration: '30s', target: 100 },  // ramp up do 100 userów
    { duration: '1m', target: 100 },   // utrzymaj 100 userów
    { duration: '30s', target: 0 },    // ramp down
  ],
};

export default function () {
  let res = http.post('http://your-vps:9090/api/v1/auth/authenticate',
    JSON.stringify({ username: 'test', password: 'test' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(1);
}
```

### 2. Apache JMeter (klasyk, GUI)

```bash
brew install jmeter
jmeter  # GUI
```

### 3. wrk (prosty, szybki)

```bash
brew install wrk
wrk -t12 -c400 -d30s http://your-vps:9090/actuator/health
# -t12 = 12 threads, -c400 = 400 connections, -d30s = 30 sekund
```

### 4. Gatling (Scala, szczegółowe raporty)

---

## Zużycie zasobów - co kosztuje RAM?

### Backend (Spring Boot na VPS)

| Zasób | Koszt per request | Koszt per user |
|-------|-------------------|----------------|
| **RAM** | ~1-5 MB per request (thread pool) | Stały - thread pool jest współdzielony |
| **CPU** | Proporcjonalny do requestów | Proporcjonalny do aktywności |
| **Połączenia DB** | Connection pool (np. 10-50 połączeń) | Współdzielone |

**Spring Boot defaults:**
- Tomcat thread pool: 200 wątków (max concurrent requests)
- Każdy wątek: ~1MB stack
- Więc ~200 równoczesnych requestów = ~200MB RAM na wątki

### Frontend (UI w przeglądarce)

| Zasób | Gdzie? | Koszt |
|-------|--------|-------|
| **RAM** | **Przeglądarka usera** | 50-200 MB per tab (nie Twój VPS!) |
| **CPU** | **Przeglądarka usera** | Lokalnie u usera |
| **Twój VPS** | Tylko serwuje pliki statyczne | Minimalne (~kilka KB per request) |

**Kluczowe**: UI w przeglądarce NIE kosztuje RAM na VPS! Cały React/Angular działa w przeglądarce użytkownika.

---

## Szacunkowe zużycie RAM na VPS

```
┌─────────────────────────────────────────────────────────┐
│                    VPS RAM Usage                        │
├─────────────────────────────────────────────────────────┤
│ JVM Heap (Spring Boot)           │ 256MB - 1GB         │
│ JVM Metaspace                    │ ~100MB              │
│ Thread Pool (200 threads)        │ ~200MB              │
│ MongoDB connections              │ ~50MB               │
│ Kafka consumer buffers           │ ~100MB              │
│ ─────────────────────────────────┼──────────────────── │
│ TOTAL dla aplikacji              │ ~700MB - 1.5GB      │
├─────────────────────────────────────────────────────────┤
│ MongoDB (jeśli na tym samym VPS) │ 1-4GB (WiredTiger)  │
│ Kafka (jeśli na tym samym VPS)   │ 1-2GB               │
└─────────────────────────────────────────────────────────┘
```

### Rekomendacje VPS

| Ilość userów | RAM VPS | vCPU | Uwagi |
|--------------|---------|------|-------|
| 1-50 | 2GB | 1 | Tylko app, DB zewnętrzne |
| 50-200 | 4GB | 2 | App + MongoDB |
| 200-500 | 8GB | 4 | App + MongoDB + Kafka |
| 500+ | 16GB+ | 8+ | Rozważ load balancer |

---

## Monitoring na VPS

### 1. Spring Boot Actuator (wbudowane)

```bash
# Zużycie pamięci JVM
curl http://your-vps:9090/actuator/metrics/jvm.memory.used

# Statystyki HTTP
curl http://your-vps:9090/actuator/metrics/http.server.requests

# Health check
curl http://your-vps:9090/actuator/health
```

### 2. Prometheus + Grafana (dashboardy)

Dodaj do `docker-compose-final.yml`:

```yaml
prometheus:
  image: prom/prometheus
  ports:
    - "9091:9090"
  volumes:
    - ./prometheus.yml:/etc/prometheus/prometheus.yml

grafana:
  image: grafana/grafana
  ports:
    - "3000:3000"
  environment:
    - GF_SECURITY_ADMIN_PASSWORD=admin
```

### 3. Prosty monitoring CLI

```bash
# RAM/CPU per process
htop

# RAM/CPU per container
docker stats

# Monitorowanie w czasie rzeczywistym
watch -n 1 'docker stats --no-stream'
```

---

## Przykładowy scenariusz testowy k6

```javascript
// vidulum-load-test.js
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration');

export let options = {
  stages: [
    { duration: '1m', target: 50 },   // Ramp up
    { duration: '3m', target: 50 },   // Steady state
    { duration: '1m', target: 100 },  // Spike
    { duration: '2m', target: 100 },  // Sustained spike
    { duration: '1m', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],  // 95% requests < 500ms
    errors: ['rate<0.1'],              // Error rate < 10%
  },
};

const BASE_URL = 'http://your-vps:9090';

export default function () {
  group('Authentication Flow', function () {
    // 1. Login
    let loginRes = http.post(`${BASE_URL}/api/v1/auth/authenticate`,
      JSON.stringify({
        username: 'testuser',
        password: 'SecurePassword123!'
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    loginDuration.add(loginRes.timings.duration);

    let success = check(loginRes, {
      'login status 200': (r) => r.status === 200,
      'has access_token': (r) => JSON.parse(r.body).access_token !== undefined,
    });

    errorRate.add(!success);

    if (success) {
      let token = JSON.parse(loginRes.body).access_token;

      // 2. Get user info
      let userRes = http.get(`${BASE_URL}/user`, {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      check(userRes, { 'get user status 200': (r) => r.status === 200 });
    }
  });

  sleep(1);
}
```

Uruchomienie:
```bash
k6 run vidulum-load-test.js
```

---

## Interpretacja wyników

### Kluczowe metryki

| Metryka | Dobra wartość | Ostrzeżenie | Krytyczne |
|---------|---------------|-------------|-----------|
| **p95 response time** | < 200ms | 200-500ms | > 500ms |
| **Error rate** | < 1% | 1-5% | > 5% |
| **Throughput (RPS)** | Zależy od VPS | - | Spadek > 20% |
| **CPU usage** | < 70% | 70-85% | > 85% |
| **RAM usage** | < 80% | 80-90% | > 90% |

### Przykładowy output k6

```
     ✓ login status 200
     ✓ has access_token
     ✓ get user status 200

     checks.........................: 100.00% ✓ 15000  ✗ 0
     data_received..................: 12 MB   67 kB/s
     data_sent......................: 3.2 MB  18 kB/s
     http_req_duration..............: avg=45ms min=12ms max=234ms p(95)=89ms
     http_reqs......................: 10000   55/s
     iteration_duration.............: avg=1.05s min=1.01s max=1.24s
     vus............................: 50      min=1    max=100
```

---

## Optymalizacja na podstawie wyników

### Jeśli response time > 500ms:
1. Sprawdź indeksy MongoDB
2. Włącz cache (Redis/Caffeine)
3. Zwiększ connection pool

### Jeśli error rate > 5%:
1. Sprawdź logi aplikacji
2. Zwiększ thread pool Tomcat
3. Sprawdź limity MongoDB connections

### Jeśli RAM > 90%:
1. Zmniejsz JVM heap (`-Xmx`)
2. Ogranicz Kafka buffer size
3. Rozważ większy VPS lub podział usług

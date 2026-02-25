# Inconsistencies and Unanswered Questions

**Powiązane:** [07-test-design.md](./07-test-design.md) | [Powrót do: 00-overview.md](./00-overview.md)

---

## 1. Znalezione niespójności w dokumentacji

### 1.1 Niespójność: Nazwa kategorii vs ID kategorii

**Źródło:** `2026-02-14-recurring-rule-engine-design.md` vs `2026-02-25-recurring-rules-technical-solutions.md`

**Problem:**
- W jednym dokumencie reguła przechowuje `categoryName: String`
- W drugim dokumencie jest wzmianka o `categoryId`
- CashFlow używa `CategoryName` jako identyfikatora (nie ma osobnego ID)

**Rekomendacja:**
Użyć `categoryName` zgodnie z modelem CashFlow. Kategorie są identyfikowane przez nazwę w ramach typu (INFLOW/OUTFLOW).

**Status:** ⚠️ Do wyjaśnienia z zespołem

---

### 1.2 Niespójność: Generowanie transakcji - PENDING vs CONFIRMED

**Źródło:** `2026-02-14-recurring-rule-engine-design.md`

**Problem:**
Dokument nie precyzuje, czy wygenerowane transakcje powinny być:
- `PENDING` (wymagające potwierdzenia przez użytkownika)
- `CONFIRMED` (automatycznie potwierdzone)

**Analiza:**
- W CashFlow istnieją oba statusy
- Dla recurring rules logiczne byłoby:
  - `PENDING` - użytkownik może zweryfikować przed potwierdzeniem
  - Lub `CONFIRMED` - automatyczne, bez ingerencji użytkownika

**Rekomendacja:**
Generować jako `PENDING` z opcją konfiguracji per-rule (`autoConfirm: boolean`).

**Status:** ❓ Wymaga decyzji produktowej

---

### 1.3 Niespójność: Data wykonania vs Data transakcji

**Źródło:** `2026-02-25-recurring-rules-edge-cases-analysis.md`

**Problem:**
Kiedy scheduler generuje transakcję 10-go o 6:00 rano:
- `dueDate` = data docelowa (np. 2026-03-10)
- `executedAt` = czas wykonania schedulera (np. 2026-03-10T06:00:00Z)
- Co jeśli scheduler jest spóźniony (np. uruchomi się 11-go)?

**Rekomendacja:**
- `dueDate` zawsze = zaplanowana data z wzorca
- `paidDate` = null (PENDING) lub dueDate (jeśli autoConfirm)
- Scheduler loguje warning przy opóźnieniu > 24h

**Status:** ✅ Rozwiązane w tym dokumencie (patrz 03-user-journeys.md)

---

### 1.4 Niespójność: Walidacja kategorii - timing

**Źródło:** `2026-02-25-recurring-rules-technical-solutions.md`

**Problem:**
Dokument opisuje walidację kategorii przy tworzeniu reguły, ale:
- Co jeśli kategoria zostanie zarchiwizowana MIĘDZY utworzeniem reguły a generowaniem transakcji?
- Event `CategoryArchivedEvent` pauzuje regułę - ale czy na pewno dojdzie przed generowaniem?

**Analiza:**
Możliwy race condition:
```
T0: Reguła aktywna, kategoria aktywna
T1: Administrator archiwizuje kategorię
T2: Event publikowany do Kafka
T3: Scheduler generuje transakcję (jeszcze przed konsumpcją eventu)
T4: Event konsumowany, reguła pauzowana
```

**Rekomendacja:**
1. Walidacja kategorii również przy generowaniu (nie tylko przy tworzeniu)
2. Jeśli kategoria nieaktywna przy generowaniu → FAILED execution + auto-pause

**Status:** ⚠️ Do implementacji

---

### 1.5 Niespójność: Orphan Rules przy usunięciu CashFlow

**Źródło:** `2026-02-25-recurring-rules-technical-solutions.md`

**Problem:**
Dokument wspomina o `OrphanDetectionService`, ale:
- Co jeśli CashFlow zostanie usunięty (hard delete) bez publikacji eventu?
- Reguły pozostaną jako "orphans"

**Rekomendacja:**
1. CashFlow MUSI publikować `CashFlowDeletedEvent` przed hard delete
2. Scheduled job do wykrywania orphans (np. co 24h)
3. Orphan rules → status COMPLETED z reason "CashFlow deleted"

**Status:** ⚠️ Częściowo rozwiązane

---

## 2. Pytania bez odpowiedzi

### 2.1 Pytanie: Multi-currency rules

**Pytanie:** Czy reguła może generować transakcje w innej walucie niż główna waluta CashFlow?

**Kontekst:**
- CashFlow ma `denomination` (główna waluta)
- Czy recurring rule może mieć `amount` w innej walucie?

**Rekomendacja:**
W pierwszej wersji: NIE. Waluta reguły = waluta CashFlow.

**Status:** ❓ Do potwierdzenia

---

### 2.2 Pytanie: Limity reguł

**Pytanie:** Czy istnieją limity na:
- Liczbę reguł per CashFlow?
- Liczbę reguł per User?
- Liczbę amount changes per rule?

**Rekomendacja:**
- Max 50 reguł per CashFlow
- Max 100 reguł per User (across all CashFlows)
- Max 24 amount changes per rule (2 lata zmian miesięcznych)

**Status:** ❓ Do uzgodnienia z produktem

---

### 2.3 Pytanie: Konflikt generowania

**Pytanie:** Co jeśli dwie reguły próbują wygenerować identyczną transakcję?

**Przykład:**
- Rule A: "Salary" na 10-go każdego miesiąca
- Rule B: "Monthly Income" na 10-go każdego miesiąca (ta sama kategoria, ta sama kwota)

**Rekomendacja:**
- Zezwolić - to decyzja użytkownika
- UI może pokazać warning przy tworzeniu podobnej reguły
- Każda transakcja ma `sourceRuleId` więc są rozróżnialne

**Status:** ✅ Rozwiązane - zezwalamy na duplikaty

---

### 2.4 Pytanie: Retroaktywne generowanie

**Pytanie:** Jeśli użytkownik tworzy regułę z `startDate` w przeszłości, czy generujemy zaległe transakcje?

**Przykład:**
- Dziś: 2026-02-25
- User tworzy regułę: startDate = 2026-01-10, monthly
- Czy generujemy transakcje dla 2026-01-10 i 2026-02-10?

**Rekomendacja:**
NIE - `startDate` musi być >= dzisiaj (walidacja `@FutureOrPresent`).
Dla importu historycznego użytkownik powinien użyć CSV import.

**Status:** ✅ Rozwiązane w API design (01-rest-api-design.md)

---

### 2.5 Pytanie: Timezone handling

**Pytanie:** W jakiej strefie czasowej są interpretowane daty?

**Kontekst:**
- `startDate`, `endDate`, `effectiveDate` są typu `LocalDate`
- Scheduler uruchamia się o 6:00 - ale której strefy?
- Co jeśli użytkownik jest w innej strefie niż serwer?

**Rekomendacja:**
1. Wszystkie daty w UTC
2. Scheduler uruchamia się o 6:00 UTC
3. Daty `LocalDate` konwertowane do początku dnia w UTC

**Status:** ⚠️ Do szczegółowej analizy

---

### 2.6 Pytanie: Pause vs End Date

**Pytanie:** Jaka jest semantyczna różnica między:
- `pause()` + `resume()`
- Ustawienie `endDate` i późniejsze usunięcie

**Rekomendacja:**
- `pause/resume` - tymczasowe wstrzymanie (np. urlop)
- `endDate` - planowane zakończenie reguły
- `pause` zachowuje historię wykonań, `endDate` to naturalne zakończenie

**Status:** ✅ Rozwiązane w domain model (02-domain-model.md)

---

### 2.7 Pytanie: Bulk operations

**Pytanie:** Czy potrzebujemy bulk operations?
- Bulk pause (np. wszystkie reguły dla CashFlow)
- Bulk delete
- Bulk update category

**Rekomendacja:**
W pierwszej wersji: NIE. Można rozważyć w przyszłości.
Wyjątek: auto-pause przy archiwizacji kategorii (to jest "bulk" ale driven przez event).

**Status:** ❓ Poza scope MVP

---

### 2.8 Pytanie: Notifications

**Pytanie:** Jakie powiadomienia powinny być wysyłane?

**Propozycja:**
| Event | Notification? | Channel |
|-------|---------------|---------|
| Rule created | ❌ | - |
| Rule auto-paused (category) | ✅ | Email + In-app |
| Execution failed | ✅ | In-app |
| Rule completed (end date) | ❌ | - |
| Upcoming execution | ❓ | TBD |

**Status:** ❓ Do uzgodnienia z produktem

---

## 3. Potencjalne problemy implementacyjne

### 3.1 Problem: Outbox table growth

**Problem:**
Outbox table będzie rosnąć z każdym eventem. Przy 1000 reguł × 12 miesięcy × 2 eventy/miesiąc = 24000 rekordów/rok.

**Rekomendacja:**
1. TTL index na `processedAt` (7 dni dla SENT)
2. Archiwizacja do osobnej kolekcji po 30 dniach
3. Monitoring rozmiaru kolekcji

**Status:** ✅ Rozwiązane w 04-mongodb-schema.md (TTL index)

---

### 3.2 Problem: Scheduler single point of failure

**Problem:**
Jeśli scheduler nie uruchomi się (awaria, deployment), transakcje nie zostaną wygenerowane.

**Rekomendacja:**
1. FailedGenerationRecoveryService co 15 minut
2. Health check dla schedulera
3. Alert jeśli scheduler nie uruchomił się > 2h
4. Manual trigger endpoint (admin only)

**Status:** ✅ Częściowo rozwiązane (recovery service)

---

### 3.3 Problem: Concurrent rule modification

**Problem:**
Race condition przy jednoczesnej edycji reguły przez:
- Użytkownika (UI)
- System (auto-pause z powodu archiwizacji kategorii)

**Rekomendacja:**
1. Optimistic locking (version field)
2. Retry logic w event handlerach
3. Clear error message dla użytkownika

**Status:** ✅ Rozwiązane (optimistic locking w domain model)

---

### 3.4 Problem: CashFlow service dependency

**Problem:**
Recurring Rules silnie zależy od CashFlow service:
- Walidacja kategorii
- Tworzenie transakcji
- Pobieranie informacji o CashFlow

**Rekomendacja:**
1. Circuit breaker (już zaimplementowany)
2. Graceful degradation:
   - Przy niedostępności: queue operations
   - Retry po przywróceniu
3. Cache kategorii (TTL 5 min)

**Status:** ⚠️ Cache do implementacji

---

## 4. Brakujące elementy w dokumentacji

### 4.1 Brak: Audit Trail

**Problem:**
Nie ma specyfikacji dla audit trail / history zmian reguły.

**Rekomendacja:**
Wykorzystać istniejące eventy domenowe jako audit log. Opcjonalnie: dedykowana kolekcja `recurring_rule_audit`.

---

### 4.2 Brak: Metrics specification

**Problem:**
Dokument technical-solutions wspomina metryki, ale nie definiuje pełnej listy.

**Rekomendacja:**
```
recurring_rules_total{status="ACTIVE|PAUSED|..."} - gauge
recurring_rules_created_total - counter
recurring_rules_executions_total{status="SUCCESS|FAILED"} - counter
recurring_rules_execution_duration_seconds - histogram
recurring_rules_cashflow_requests_total{status="2xx|4xx|5xx"} - counter
```

---

### 4.3 Brak: Migration strategy

**Problem:**
Brak specyfikacji jak migrować istniejące dane (jeśli są) lub jak wdrożyć feature flag.

**Rekomendacja:**
1. Feature flag: `recurring-rules.enabled=false` (domyślnie)
2. Canary deployment na subset userów
3. Gradual rollout (10% → 50% → 100%)

---

### 4.4 Brak: API versioning strategy

**Problem:**
Nie określono strategii wersjonowania API dla przyszłych zmian.

**Rekomendacja:**
URL-based versioning: `/api/v1/recurring-rules`, `/api/v2/recurring-rules`

---

## 5. Rekomendacje dla UI

### 5.1 UI powinien pokazywać

Na podstawie API responses, UI powinien wyświetlać:

| Ekran | Dane z API | Uwagi |
|-------|------------|-------|
| Lista reguł | `name`, `amount`, `nextOccurrence`, `status` | Sortowanie po `createdAt` |
| Szczegóły reguły | Wszystkie pola + `amountChanges` + `executionHistory` | Timeline zmian kwot |
| Podgląd usunięcia | `impact.futureOccurrences`, `warnings` | Modal z ostrzeżeniami |
| Edycja reguły | Formularz + `affectedOccurrences` preview | Real-time preview |

### 5.2 UI mockup validation

Sprawdzić czy mockupy zawierają:
- [ ] Stan `PAUSED` z przyczyną pauzy
- [ ] Timeline amount changes
- [ ] Historia wykonań (success/failure)
- [ ] Preview wpływu zmian
- [ ] Obsługa błędów (503, 404)

---

## 6. Podsumowanie priorytetów

### 6.1 Blokery (muszą być rozwiązane przed implementacją)

| # | Problem | Priorytet |
|---|---------|-----------|
| 1 | Timezone handling | 🔴 Critical |
| 2 | Race condition przy generowaniu (kategoria zarchiwizowana) | 🔴 Critical |
| 3 | Status generowanych transakcji (PENDING vs CONFIRMED) | 🔴 Critical |

### 6.2 Do uzgodnienia z produktem

| # | Pytanie | Priorytet |
|---|---------|-----------|
| 1 | Limity reguł | 🟡 High |
| 2 | Notifications | 🟡 High |
| 3 | Multi-currency | 🟢 Low (MVP: single currency) |
| 4 | Bulk operations | 🟢 Low (post-MVP) |

### 6.3 Tech debt / improvements

| # | Improvement | Priorytet |
|---|-------------|-----------|
| 1 | Category cache | 🟡 High |
| 2 | Audit trail | 🟢 Low |
| 3 | Full metrics spec | 🟡 Medium |
| 4 | API versioning strategy | 🟢 Low |

---

## 7. Decyzje do podjęcia

Poniższe decyzje wymagają dyskusji z zespołem/stakeholderami:

```
[ ] 1. Timezone strategy (UTC vs user timezone)
[ ] 2. Generated transaction status (PENDING vs CONFIRMED vs configurable)
[ ] 3. Rule limits (per CashFlow, per User)
[ ] 4. Notification channels i triggers
[ ] 5. Cache strategy dla kategorii
[ ] 6. Feature flag rollout plan
```

---

## Powrót do głównego dokumentu

[← 00-overview.md](./00-overview.md)

# Vidulum - Docker Setup & API Guide

## Quick Start

### 1. Build Application
```bash
./mvnw clean package -DskipTests
```

### 2. Build Docker Image
```bash
docker build -t vidulum:latest .
```

### 3. Start All Services
```bash
docker-compose -f docker-compose-final.yml up -d
```

### 4. Check Status
```bash
docker-compose -f docker-compose-final.yml ps
```

### 5. View Application Logs
```bash
docker logs -f vidulum-app
```

### 6. Stop All Services
```bash
docker-compose -f docker-compose-final.yml down
```

---

## Services & Ports

| Service | URL | Description |
|---------|-----|-------------|
| **Vidulum API** | http://localhost:9090 | Main application |
| **MongoDB** | localhost:27017 | Database |
| **Kafka** | localhost:9092 | Message broker |
| **Kafdrop** | http://localhost:9000 | Kafka UI |
| **Zookeeper** | localhost:2181 | Kafka coordination |

---

## API Endpoints & Examples

### Authentication

#### Register User
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "testuser@example.com",
    "password": "SecurePass123",
    "role": "MANAGER"
  }'
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

#### Login
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "SecurePass123"
  }'
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

---

### User Management

#### Get Current User
```bash
curl -X GET http://localhost:9090/user \
  -H "Authorization: Bearer <access_token>"
```

**Response:**
```json
{
  "userId": "695926267bd92300ad1eafcf",
  "username": "testuser",
  "email": "testuser@example.com",
  "portolioIds": [],
  "active": true
}
```

---

### CashFlow Management

#### Create CashFlow
```bash
curl -X POST http://localhost:9090/cash-flow \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "<user_id>",
    "name": "Main Account",
    "description": "My primary bank account",
    "bankAccount": {
      "name": "PKO Bank",
      "accountNumber": "PL61109010140000071219812874",
      "balance": {
        "value": 5000.00,
        "currency": "PLN"
      }
    }
  }'
```

**Response:** CashFlow ID (string)
```
bf98220f-6852-4e8c-aea4-dcda42a2e4be
```

#### Get CashFlow
```bash
curl -X GET http://localhost:9090/cash-flow/<cashflow_id> \
  -H "Authorization: Bearer <access_token>"
```

#### Get All CashFlows for User
```bash
curl -X GET http://localhost:9090/cash-flow/viaUser/<user_id> \
  -H "Authorization: Bearer <access_token>"
```

---

### Category Management

#### Create Category (OUTFLOW - expenses)
```bash
curl -X POST http://localhost:9090/cash-flow/<cashflow_id>/category \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "category": "Groceries",
    "type": "OUTFLOW"
  }'
```

#### Create Category (INFLOW - income)
```bash
curl -X POST http://localhost:9090/cash-flow/<cashflow_id>/category \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "category": "Salary",
    "type": "INFLOW"
  }'
```

#### Create Subcategory
```bash
curl -X POST http://localhost:9090/cash-flow/<cashflow_id>/category \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "parentCategoryName": "Groceries",
    "category": "Vegetables",
    "type": "OUTFLOW"
  }'
```

---

### CashChange (Transactions)

#### Add Expense (OUTFLOW)
```bash
curl -X POST http://localhost:9090/cash-flow/cash-change \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "category": "Groceries",
    "name": "Weekly groceries",
    "description": "Groceries from Biedronka",
    "money": {
      "value": 250.00,
      "currency": "PLN"
    },
    "type": "OUTFLOW",
    "dueDate": "2026-01-05T10:00:00Z"
  }'
```

**Response:** CashChange ID (string)
```
2de5bcc5-d847-4606-afb8-c9b01bccea9d
```

#### Add Income (INFLOW)
```bash
curl -X POST http://localhost:9090/cash-flow/cash-change \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "category": "Salary",
    "name": "January Salary",
    "description": "Monthly salary",
    "money": {
      "value": 8500.00,
      "currency": "PLN"
    },
    "type": "INFLOW",
    "dueDate": "2026-01-10T09:00:00Z"
  }'
```

#### Confirm CashChange
```bash
curl -X POST http://localhost:9090/cash-flow/confirm \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "cashChangeId": "<cashchange_id>"
  }'
```

#### Edit CashChange
```bash
curl -X POST http://localhost:9090/cash-flow/edit \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "cashChangeId": "<cashchange_id>",
    "name": "Updated name",
    "description": "Updated description",
    "money": {
      "value": 300.00,
      "currency": "PLN"
    },
    "dueDate": "2026-01-06T10:00:00Z"
  }'
```

#### Reject CashChange
```bash
curl -X POST http://localhost:9090/cash-flow/reject \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "cashChangeId": "<cashchange_id>",
    "reason": "Duplicate entry"
  }'
```

---

### Budgeting

#### Set Budget for Category
```bash
curl -X POST http://localhost:9090/cash-flow/budgeting \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "categoryName": "Groceries",
    "categoryType": "OUTFLOW",
    "budget": {
      "value": 1000.00,
      "currency": "PLN"
    }
  }'
```

#### Update Budget
```bash
curl -X PUT http://localhost:9090/cash-flow/budgeting \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "categoryName": "Groceries",
    "categoryType": "OUTFLOW",
    "newBudget": {
      "value": 1200.00,
      "currency": "PLN"
    }
  }'
```

#### Remove Budget
```bash
curl -X DELETE http://localhost:9090/cash-flow/budgeting \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "cashFlowId": "<cashflow_id>",
    "categoryName": "Groceries",
    "categoryType": "OUTFLOW"
  }'
```

---

## Data Types Reference

### Type (Category/Transaction type)
- `INFLOW` - Income/deposit
- `OUTFLOW` - Expense/withdrawal

### CashChangeStatus
- `PENDING` - Awaiting confirmation
- `CONFIRMED` - Confirmed transaction
- `REJECTED` - Rejected transaction

### Role (User role)
- `MANAGER` - Standard user
- `ADMIN` - Administrator

### Money Object
```json
{
  "value": 100.00,
  "currency": "PLN"
}
```

### BankAccount Object
```json
{
  "name": "Bank Name",
  "accountNumber": "PL61109010140000071219812874",
  "balance": {
    "value": 5000.00,
    "currency": "PLN"
  }
}
```

---

## Troubleshooting

### Application not starting
```bash
# Check logs
docker logs vidulum-app

# Verify all containers are running
docker-compose -f docker-compose-final.yml ps
```

### Kafka issues
```bash
# Check Kafka logs
docker logs kafka

# Access Kafdrop UI
open http://localhost:9000
```

### MongoDB connection issues
```bash
# Check MongoDB logs
docker logs mongodb

# Connect to MongoDB shell
docker exec -it mongodb mongosh
```

### Rebuild after code changes
```bash
./mvnw clean package -DskipTests && \
docker build -t vidulum:latest . && \
docker-compose -f docker-compose-final.yml up -d
```

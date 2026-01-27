# Authentication & Validation - Manual Test Report

## Test Execution Date: 2026-01-27

## Summary

This report documents manual testing of authentication endpoints with standardized validation and error handling.

### Features Tested

| Feature | Status |
|---------|--------|
| Registration with valid data | PASS |
| Registration validation (username, email, password, role) | PASS |
| Duplicate email handling (409 Conflict) | PASS |
| Authentication with valid credentials | PASS |
| Authentication validation | PASS |
| Invalid credentials handling (401 Unauthorized) | PASS |
| Invalid JSON handling | PASS |

---

## Test Environment

| Component | Value |
|-----------|-------|
| Date | 2026-01-27 |
| Base URL | `http://localhost:9090` |
| Docker Image | `vidulum-app:latest` |
| Docker Network | `vidulum_default` |

---

## API Error Response Structure

All error responses follow a standardized format:

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:25.690Z",
  "fieldErrors": [
    {"field": "username", "message": "Username is required"}
  ]
}
```

### Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VALIDATION_ERROR` | 400 | Request validation failed |
| `VALIDATION_INVALID_JSON` | 400 | Invalid JSON format |
| `AUTH_EMAIL_TAKEN` | 409 | Email is already registered |
| `AUTH_INVALID_CREDENTIALS` | 401 | Invalid username or password |

---

# SCENARIO 1: Registration Endpoint

## Test Case 1.1: Successful Registration

### Endpoint
```
POST /api/v1/auth/register
```

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser2",
    "email": "testuser2@example.com",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0dXNlcjIiLCJpYXQiOjE3Njk1NTI1OTcsImV4cCI6MTc2OTYzODk5N30.jbS_ezt2k3dLGgN3iXORBNZ4u4QVlxs0Zoh911W2f-s",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0dXNlcjIiLCJpYXQiOjE3Njk1NTI1OTcsImV4cCI6MTc3MDE1NzM5N30.0HYbxD458cz8vOR98hTFpAd9PaWg9Kn7ddlCBpR04P0"
}
```

### Validation
- [x] Status: **200 OK**
- [x] Access token returned (JWT format)
- [x] Refresh token returned (JWT format)

---

## Test Case 1.2: Empty Request Body

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{}'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:25.690790634Z",
  "fieldErrors": [
    {"field": "username", "message": "Username is required"},
    {"field": "email", "message": "Email is required"},
    {"field": "role", "message": "Role is required"},
    {"field": "password", "message": "Password is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Code: `VALIDATION_ERROR`
- [x] All 4 required fields reported

---

## Test Case 1.3: Missing Username

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:26.384971717Z",
  "fieldErrors": [
    {"field": "username", "message": "Username is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Field error for `username` with message "Username is required"

---

## Test Case 1.4: Username Too Short (2 characters)

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "ab",
    "email": "test4@example.com",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:27.097455009Z",
  "fieldErrors": [
    {"field": "username", "message": "Username must be between 3 and 50 characters"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Size constraint message displayed

---

## Test Case 1.5: Invalid Username Characters

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user@name!",
    "email": "test5@example.com",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:27.827211051Z",
  "fieldErrors": [
    {"field": "username", "message": "Username can only contain letters, numbers and underscores"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Pattern constraint message displayed

---

## Test Case 1.6: Missing Email

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser6",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:36.581942541Z",
  "fieldErrors": [
    {"field": "email", "message": "Email is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Field error for `email` with message "Email is required"

---

## Test Case 1.7: Invalid Email Format

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser7",
    "email": "invalid-email",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:37.292980750Z",
  "fieldErrors": [
    {"field": "email", "message": "Invalid email format"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Email format validation message displayed

---

## Test Case 1.8: Password Too Short (5 characters)

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser8",
    "email": "test8@example.com",
    "password": "short",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:38.061707875Z",
  "fieldErrors": [
    {"field": "password", "message": "Password must be between 8 and 100 characters"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Password size constraint message displayed (min 8 chars)

---

## Test Case 1.9: Missing Role

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser9",
    "email": "test9@example.com",
    "password": "password123"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:38.795370001Z",
  "fieldErrors": [
    {"field": "role", "message": "Role is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Field error for `role` with message "Role is required"

---

## Test Case 1.10: Duplicate Email (409 Conflict)

### Request
```bash
# First register with testuser2@example.com (Test Case 1.1)
# Then try to register another user with the same email
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "anotheruser",
    "email": "testuser2@example.com",
    "password": "password123",
    "role": "USER"
  }'
```

### Response
```json
{
  "status": 409,
  "code": "AUTH_EMAIL_TAKEN",
  "message": "Email 'testuser2@example.com' is already registered",
  "timestamp": "2026-01-27T22:23:47.630474547Z"
}
```

### Validation
- [x] Status: **409 Conflict**
- [x] Code: `AUTH_EMAIL_TAKEN`
- [x] Message includes the duplicate email address
- [x] No `fieldErrors` (this is a business error, not validation)

---

## Test Case 1.11: Invalid JSON

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{invalid json}'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_INVALID_JSON",
  "message": "Invalid JSON format",
  "timestamp": "2026-01-27T22:24:00.847940720Z"
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Code: `VALIDATION_INVALID_JSON`
- [x] No `fieldErrors` (JSON couldn't be parsed)

---

# SCENARIO 2: Authentication Endpoint

## Test Case 2.1: Successful Authentication

### Endpoint
```
POST /api/v1/auth/authenticate
```

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser2",
    "password": "password123"
  }'
```

### Response
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0dXNlcjIiLCJpYXQiOjE3Njk1NTI2MjgsImV4cCI6MTc2OTYzOTAyOH0.dtBQWv3gV3X2IRVdSzqZKLVCsbZuAzW5viPgyzk3bsQ",
  "refresh_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0dXNlcjIiLCJpYXQiOjE3Njk1NTI2MjgsImV4cCI6MTc3MDE1NzQyOH0.AwRzfju3SwxKPNDrP_RDqBTqYR4bo5mnuYM030rfW7E"
}
```

### Validation
- [x] Status: **200 OK**
- [x] New access token returned
- [x] New refresh token returned

---

## Test Case 2.2: Empty Request Body

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{}'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:49.293197006Z",
  "fieldErrors": [
    {"field": "password", "message": "Password is required"},
    {"field": "username", "message": "Username is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Code: `VALIDATION_ERROR`
- [x] Both required fields reported

---

## Test Case 2.3: Missing Username

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "password": "password123"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:50.053341506Z",
  "fieldErrors": [
    {"field": "username", "message": "Username is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Field error for `username`

---

## Test Case 2.4: Missing Password

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser2"
  }'
```

### Response
```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-01-27T22:23:58.433175802Z",
  "fieldErrors": [
    {"field": "password", "message": "Password is required"}
  ]
}
```

### Validation
- [x] Status: **400 Bad Request**
- [x] Field error for `password`

---

## Test Case 2.5: Invalid Credentials (Wrong Password)

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser2",
    "password": "wrongpassword"
  }'
```

### Response
```json
{
  "status": 401,
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "Invalid username or password",
  "timestamp": "2026-01-27T22:23:59.234417552Z"
}
```

### Validation
- [x] Status: **401 Unauthorized**
- [x] Code: `AUTH_INVALID_CREDENTIALS`
- [x] Generic message (no hint about which field is wrong for security)
- [x] No `fieldErrors`

---

## Test Case 2.6: Invalid Credentials (Nonexistent User)

### Request
```bash
curl -X POST http://localhost:9090/api/v1/auth/authenticate \
  -H "Content-Type: application/json" \
  -d '{
    "username": "nonexistent_user",
    "password": "password123"
  }'
```

### Response
```json
{
  "status": 401,
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "Invalid username or password",
  "timestamp": "2026-01-27T22:24:00.072092511Z"
}
```

### Validation
- [x] Status: **401 Unauthorized**
- [x] Code: `AUTH_INVALID_CREDENTIALS`
- [x] Same message as wrong password (no user enumeration)

---

# Test Results Summary

## Registration Tests

| Test Case | Description | Expected Status | Actual Status | Result |
|-----------|-------------|-----------------|---------------|--------|
| 1.1 | Successful registration | 200 | 200 | PASS |
| 1.2 | Empty request body | 400 | 400 | PASS |
| 1.3 | Missing username | 400 | 400 | PASS |
| 1.4 | Username too short | 400 | 400 | PASS |
| 1.5 | Invalid username characters | 400 | 400 | PASS |
| 1.6 | Missing email | 400 | 400 | PASS |
| 1.7 | Invalid email format | 400 | 400 | PASS |
| 1.8 | Password too short | 400 | 400 | PASS |
| 1.9 | Missing role | 400 | 400 | PASS |
| 1.10 | Duplicate email | 409 | 409 | PASS |
| 1.11 | Invalid JSON | 400 | 400 | PASS |

## Authentication Tests

| Test Case | Description | Expected Status | Actual Status | Result |
|-----------|-------------|-----------------|---------------|--------|
| 2.1 | Successful authentication | 200 | 200 | PASS |
| 2.2 | Empty request body | 400 | 400 | PASS |
| 2.3 | Missing username | 400 | 400 | PASS |
| 2.4 | Missing password | 400 | 400 | PASS |
| 2.5 | Wrong password | 401 | 401 | PASS |
| 2.6 | Nonexistent user | 401 | 401 | PASS |

---

## Overall Test Summary

| Category | Total | Passed | Failed |
|----------|-------|--------|--------|
| Registration | 11 | 11 | 0 |
| Authentication | 6 | 6 | 0 |
| **Total** | **17** | **17** | **0** |

**Pass Rate: 100%**

---

## Validation Rules Summary

### Username
- Required (cannot be null or blank)
- Min length: 3 characters
- Max length: 50 characters
- Pattern: `^[a-zA-Z0-9_]+$` (letters, numbers, underscores only)

### Email
- Required (cannot be null or blank)
- Must be valid email format

### Password
- Required (cannot be null or blank)
- Min length: 8 characters
- Max length: 100 characters

### Role
- Required (cannot be null)
- Valid values: USER, ADMIN, MANAGER

---

## Security Notes

1. **Invalid credentials response**: The same error message "Invalid username or password" is returned for both wrong password and nonexistent user cases to prevent user enumeration attacks.

2. **Email uniqueness**: Duplicate email returns 409 Conflict with the `AUTH_EMAIL_TAKEN` error code.

3. **No sensitive data in errors**: Error responses never include internal details like stack traces.

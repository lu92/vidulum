# Authentication System Documentation

This document describes the JWT-based authentication system implemented in Vidulum.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Endpoints](#endpoints)
- [Token Types](#token-types)
- [Token Rotation](#token-rotation)
- [Error Handling](#error-handling)
- [Security Considerations](#security-considerations)
- [Compliance](#compliance)
- [Examples](#examples)

## Overview

Vidulum uses a dual-token JWT authentication system:

- **Access Token**: Short-lived token (24 hours) for API authentication
- **Refresh Token**: Long-lived token (7 days) for obtaining new access tokens

All tokens are stored in MongoDB with their status (valid/revoked/expired), enabling server-side token invalidation.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Authentication Flow                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────┐     Register/Login      ┌────────────────────┐               │
│   │  Client  │ ──────────────────────► │ AuthController     │               │
│   │          │                          │ /api/v1/auth/*     │               │
│   │          │ ◄────────────────────── │                    │               │
│   │          │   Access + Refresh Token │                    │               │
│   └──────────┘                          └─────────┬──────────┘               │
│        │                                          │                          │
│        │ Protected Request                        ▼                          │
│        │ (Bearer Token)              ┌────────────────────────┐              │
│        │                              │ AuthenticationService  │              │
│        ▼                              │                        │              │
│   ┌──────────────────┐                │ - register()           │              │
│   │ JwtAuthFilter    │                │ - authenticate()       │              │
│   │                  │                │ - logout()             │              │
│   │ 1. Extract token │                │ - logoutAllDevices()   │              │
│   │ 2. Validate JWT  │                │ - refreshToken()       │              │
│   │ 3. Check revoked │                └─────────┬──────────────┘              │
│   └──────────────────┘                          │                            │
│                                                 ▼                            │
│                                    ┌────────────────────────┐                │
│                                    │    TokenRepository     │                │
│                                    │      (MongoDB)         │                │
│                                    │                        │                │
│                                    │ - Token storage        │                │
│                                    │ - Revocation tracking  │                │
│                                    │ - Audit timestamps     │                │
│                                    └────────────────────────┘                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Endpoints

### Base URL
```
/api/v1/auth
```

### 1. Register User

Creates a new user account and returns authentication tokens.

**Endpoint:** `POST /api/v1/auth/register`

**Request Body:**
```json
{
  "username": "string (3-50 chars, alphanumeric + underscore)",
  "email": "string (valid email format)",
  "password": "string (8-100 chars)"
}
```

**Success Response (200 OK):**
```json
{
  "user_id": "U10000001",
  "access_token": "eyJhbGciOiJIUzM4NCJ9...",
  "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
}
```

**Error Responses:**
- `400 BAD_REQUEST` - Validation errors (invalid username/email/password format)
- `409 CONFLICT` - Email already registered

### 2. Authenticate (Login)

Authenticates user credentials and returns new tokens. Old tokens are automatically revoked.

**Endpoint:** `POST /api/v1/auth/authenticate`

**Request Body:**
```json
{
  "username": "string",
  "password": "string"
}
```

**Success Response (200 OK):**
```json
{
  "user_id": "U10000001",
  "access_token": "eyJhbGciOiJIUzM4NCJ9...",
  "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
}
```

**Error Responses:**
- `400 BAD_REQUEST` - Validation errors
- `401 UNAUTHORIZED` - Invalid credentials

### 3. Refresh Token

Exchanges a valid refresh token for new access and refresh tokens (token rotation).

**Endpoint:** `POST /api/v1/auth/refresh-token`

**Request Headers:**
```
Authorization: Bearer <refresh_token>
```

**Success Response (200 OK):**
```json
{
  "user_id": "U10000001",
  "access_token": "eyJhbGciOiJIUzM4NCJ9...",
  "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
}
```

**Error Responses:**
- `400 BAD_REQUEST` - Missing Authorization header
- `401 UNAUTHORIZED` - Token expired, revoked, or invalid

### 4. Logout

Revokes all tokens for the current user, effectively logging them out.

**Endpoint:** `POST /api/v1/auth/logout`

**Request Headers:**
```
Authorization: Bearer <access_token>
```

**Success Response (200 OK):**
```json
{
  "message": "Successfully logged out",
  "user_id": "U10000001"
}
```

**Error Responses:**
- `400 BAD_REQUEST` - Missing Authorization header
- `401 UNAUTHORIZED` - Token already revoked
- `404 NOT_FOUND` - Token not found

### 5. Logout All Devices

Revokes all tokens across all devices for the user.

**Endpoint:** `POST /api/v1/auth/logout-all`

**Request Headers:**
```
Authorization: Bearer <access_token>
```

**Success Response (200 OK):**
```json
{
  "message": "Successfully logged out from all devices",
  "user_id": "U10000001",
  "revoked_sessions_count": 4
}
```

**Error Responses:**
- `400 BAD_REQUEST` - Missing Authorization header
- `401 UNAUTHORIZED` - Token already revoked

## Token Types

### Access Token (BEARER)

- **Purpose**: API authentication for protected endpoints
- **Expiration**: 24 hours
- **Storage**: MongoDB `token` collection
- **Claims**:
  - `jti`: Unique token identifier (UUID)
  - `sub`: Username
  - `iat`: Issued at timestamp
  - `exp`: Expiration timestamp

### Refresh Token (REFRESH)

- **Purpose**: Obtain new access tokens without re-authentication
- **Expiration**: 7 days
- **Storage**: MongoDB `token` collection
- **Claims**: Same as access token

### Token Document Structure (MongoDB)

```javascript
{
  "_id": "token_id",
  "token": "eyJhbGciOiJIUzM4NCJ9...",
  "tokenType": "BEARER" | "REFRESH",
  "userId": "U10000001",
  "revoked": false,
  "expired": false,
  "createdAt": ISODate("2026-02-24T10:00:00.000Z")
}
```

## Token Rotation

Token rotation is a security feature that generates new tokens on refresh:

1. User calls `/refresh-token` with valid refresh token
2. System validates the refresh token (JWT signature + database status)
3. System revokes ALL old tokens for the user
4. System generates new access and refresh tokens
5. New tokens are stored in database and returned to client

**Benefits:**
- Limits window of vulnerability if tokens are stolen
- Provides audit trail of token usage
- Enables detection of token replay attacks

## Error Handling

### Error Response Format

All errors follow a consistent format:

```json
{
  "status": 401,
  "code": "AUTH_TOKEN_REVOKED",
  "message": "Token has been revoked",
  "timestamp": "2026-02-24T10:00:00.000Z",
  "fieldErrors": []  // Optional, only for validation errors
}
```

### Authentication Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `AUTH_INVALID_CREDENTIALS` | 401 | Invalid username or password |
| `AUTH_EMAIL_TAKEN` | 409 | Email already registered |
| `AUTH_TOKEN_NOT_FOUND` | 404 | Token not found in database |
| `AUTH_TOKEN_REVOKED` | 401 | Token has been revoked |
| `AUTH_TOKEN_INVALID` | 401 | Token is malformed or invalid |
| `AUTH_REFRESH_TOKEN_EXPIRED` | 401 | Refresh token has expired |
| `AUTH_MISSING_TOKEN` | 400 | Authorization header missing |
| `VALIDATION_ERROR` | 400 | Request validation failed |

## Security Considerations

### Token Security

1. **Server-side token validation**: Every token is validated against the database
2. **Unique token IDs (JTI)**: Each token has a unique identifier preventing duplicates
3. **Token rotation**: Refresh operations generate new tokens
4. **Immediate revocation**: Logout immediately invalidates all user tokens

### Best Practices

1. **Store tokens securely**: Use HttpOnly cookies or secure storage
2. **Implement token refresh**: Refresh tokens before they expire
3. **Handle logout properly**: Clear tokens on client side after logout
4. **Use HTTPS**: All authentication endpoints require HTTPS in production

### Configuration

Application properties:
```yaml
application:
  security:
    jwt:
      secret-key: ${JWT_SECRET_KEY}  # Base64 encoded secret
      expiration: 86400000           # 24 hours in milliseconds
      refresh-token:
        expiration: 604800000        # 7 days in milliseconds
```

## Compliance

### Audit Trail (GDPR, SOC2)

The system maintains comprehensive audit trails for compliance:

1. **User Registration**: Logged with userId and username
2. **User Authentication**: Logged with userId and username
3. **Token Refresh**: Logged with userId and username
4. **Logout**: Logged with userId and count of revoked tokens
5. **Logout All Devices**: Logged with userId and count of revoked sessions

**Log Format:**
```
INFO  User registered: userId=U10000001, username=john_doe
INFO  User authenticated: userId=U10000001, username=john_doe
INFO  Token refreshed: userId=U10000001, username=john_doe
INFO  User logged out: userId=U10000001, revokedTokens=2
WARN  User logged out from ALL devices: userId=U10000001, revokedTokens=4
```

### GDPR Considerations

- Token data includes `createdAt` timestamp for audit purposes
- Users can revoke all their tokens via `/logout-all`
- Token revocation is permanent and immediate

### SOC2 Considerations

- All authentication events are logged
- Token status is tracked in database (audit trail)
- Logout operations include revocation count
- Security-sensitive operations logged at WARN level

## Examples

### Complete Authentication Flow (curl)

```bash
# 1. Register new user
curl -X POST http://localhost:9090/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "john_doe",
    "email": "john@example.com",
    "password": "SecurePassword123"
  }'

# Response:
# {
#   "user_id": "U10000001",
#   "access_token": "eyJhbGciOiJIUzM4NCJ9...",
#   "refresh_token": "eyJhbGciOiJIUzM4NCJ9..."
# }

# 2. Access protected resource
curl -X GET http://localhost:9090/cash-flow \
  -H "Authorization: Bearer eyJhbGciOiJIUzM4NCJ9..."

# 3. Refresh tokens
curl -X POST http://localhost:9090/api/v1/auth/refresh-token \
  -H "Authorization: Bearer <refresh_token>"

# 4. Logout
curl -X POST http://localhost:9090/api/v1/auth/logout \
  -H "Authorization: Bearer <access_token>"

# 5. Logout from all devices
curl -X POST http://localhost:9090/api/v1/auth/logout-all \
  -H "Authorization: Bearer <access_token>"
```

### Error Handling Example

```bash
# Try to use revoked token
curl -X POST http://localhost:9090/api/v1/auth/logout \
  -H "Authorization: Bearer <revoked_token>"

# Response:
# {
#   "status": 401,
#   "code": "AUTH_TOKEN_REVOKED",
#   "message": "Token has been revoked",
#   "timestamp": "2026-02-24T10:00:00.000Z"
# }
```

### JavaScript/TypeScript Client Example

```typescript
class AuthClient {
  private baseUrl = '/api/v1/auth';
  private accessToken: string | null = null;
  private refreshToken: string | null = null;

  async register(username: string, email: string, password: string) {
    const response = await fetch(`${this.baseUrl}/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password })
    });
    const data = await response.json();
    this.accessToken = data.access_token;
    this.refreshToken = data.refresh_token;
    return data;
  }

  async login(username: string, password: string) {
    const response = await fetch(`${this.baseUrl}/authenticate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const data = await response.json();
    this.accessToken = data.access_token;
    this.refreshToken = data.refresh_token;
    return data;
  }

  async refreshTokens() {
    const response = await fetch(`${this.baseUrl}/refresh-token`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.refreshToken}` }
    });
    const data = await response.json();
    this.accessToken = data.access_token;
    this.refreshToken = data.refresh_token;
    return data;
  }

  async logout() {
    await fetch(`${this.baseUrl}/logout`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.accessToken}` }
    });
    this.accessToken = null;
    this.refreshToken = null;
  }

  async logoutAll() {
    const response = await fetch(`${this.baseUrl}/logout-all`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${this.accessToken}` }
    });
    const data = await response.json();
    this.accessToken = null;
    this.refreshToken = null;
    return data;
  }

  getAuthHeader() {
    return { 'Authorization': `Bearer ${this.accessToken}` };
  }
}
```

## Testing

The authentication system includes comprehensive integration tests:

- **27 test cases** covering all endpoints and scenarios
- Test classes organized by feature:
  - `RegistrationSuccess` - Successful registration scenarios
  - `RegistrationValidation` - Input validation tests
  - `RegistrationBusinessErrors` - Business rule violations
  - `AuthenticationSuccess` - Successful login scenarios
  - `AuthenticationValidation` - Login validation tests
  - `AuthenticationBusinessErrors` - Invalid credentials
  - `LogoutSuccess` - Successful logout scenarios
  - `LogoutValidation` - Logout without token
  - `LogoutBusinessErrors` - Double logout
  - `LogoutAllDevices` - Multi-device logout
  - `RefreshTokenSuccess` - Token refresh and rotation
  - `RefreshTokenErrors` - Invalid refresh scenarios

Run tests:
```bash
./mvnw test -Dtest=AuthenticationControllerTest
```

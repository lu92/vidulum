# Dokploy Troubleshooting

## Problem: Domain routing to Dokploy dashboard instead of application

### Symptoms
- Accessing `https://widlum.com` shows Dokploy 400 error page
- Application is running (container healthy) but not accessible via domain

### Root Cause
Dokploy generates incorrect Traefik configuration in `/etc/dokploy/traefik/dynamic/dokploy.yml`, pointing to `dokploy:3000` (dashboard) instead of the application container.

### Solution

SSH to server and edit the Traefik config:

```bash
ssh root@45.67.217.7
nano /etc/dokploy/traefik/dynamic/dokploy.yml
```

Replace content with:

```yaml
http:
  routers:
    dokploy-router-app:
      rule: Host(`widlum.com`)
      service: vidulum-app-service
      entryPoints:
        - web
      middlewares:
        - redirect-to-https
    dokploy-router-app-secure:
      rule: Host(`widlum.com`)
      service: vidulum-app-service
      entryPoints:
        - websecure
      tls:
        certResolver: letsencrypt
  services:
    vidulum-app-service:
      loadBalancer:
        servers:
          - url: http://widlum-backend-accsfo-vidulum-app-1:8080
        passHostHeader: true
```

Traefik will auto-reload within seconds.

### Verification

```bash
# Test direct connection
curl -k https://45.67.217.7/actuator/health -H "Host: widlum.com"

# Test via Cloudflare
curl https://widlum.com/actuator/health
```

Expected: `{"status":"UP"}`

### Warning
After Redeploy in Dokploy, the `dokploy.yml` file may be overwritten. Check and fix if needed.

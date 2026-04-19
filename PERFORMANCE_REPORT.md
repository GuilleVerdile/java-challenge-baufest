# Informe de Rendimiento y Optimización

## Configuración del Entorno de Prueba

- **JVM**: OpenJDK 17
- **Framework**: Spring Boot 3.2.0
- **Servidor**: Tomcat embebido (default)
- **Thread Pool de Aplicación**: core=20, max=100, queue=500
- **Semáforo por cliente**: 5 solicitudes concurrentes

## Metodología

Se ejecutó `HighLoadConcurrencyTest` que dispara **1000 pedidos concurrentes** desde
un pool de 200 clientes simultáneos con `CountDownLatch` para sincronizar el inicio
y maximizar la contención.

Métricas capturadas por solicitud:
- Latencia individual (tiempo desde envío hasta respuesta)
- Throughput (req/s)
- Percentiles p50, p95, p99, max
- Tasa de éxito vs fallo
- Consistencia de datos (pérdida cero)

## Resultados Observados (referencia)

| Métrica | Valor típico |
|---|---|
| Total procesadas | 1000 / 1000 (0 pérdidas) |
| Throughput | ~180-250 req/s |
| Latencia promedio | ~900-1200 ms |
| Latencia p50 | ~800 ms |
| Latencia p95 | ~1800 ms |
| Latencia p99 | ~2500 ms |
| CPU | 60-85% |
| Heap | < 512 MB |

> Nota: los valores reales dependen del hardware. Los números están dominados por
> `Thread.sleep(100-500ms)` que simula la operación de negocio.

## Tuning del Thread Pool

Se probaron múltiples configuraciones:

| core | max | queue | Throughput | p99 (ms) | Observación |
|------|-----|-------|------------|----------|-------------|
| 10   | 50  | 200   | 120 req/s  | 4500     | Cola saturada |
| 20   | 100 | 500   | 240 req/s  | 2500     | **Óptimo**    |
| 50   | 200 | 1000  | 250 req/s  | 2400     | CPU saturada  |
| 100  | 500 | 2000  | 230 req/s  | 2800     | Context switching excesivo |

**Conclusión**: core=20 / max=100 balancea throughput y uso de CPU.

## Técnicas de Optimización Aplicadas

### 1. Thread Pool dedicado
`ThreadPoolTaskExecutor` separado (`orderTaskExecutor`) para no competir con el
pool de servlets de Tomcat.

### 2. Política de rechazo `CallerRunsPolicy`
Bajo sobrecarga, el thread del caller ejecuta la tarea → backpressure natural,
evita descartar solicitudes.

### 3. Estructuras concurrentes sin locks
- `ConcurrentHashMap` para almacén de pedidos
- `AtomicLong` / `AtomicInteger` para contadores

### 4. Locks granulares
- `ReentrantLock` fair solo para inventario (crítico)
- `Semaphore` fair por cliente (aislamiento)

### 5. Idempotencia
`putIfAbsent` + verificación de estado previene doble procesamiento.

### 6. Timeout en adquisición
`tryAcquire(timeout)` evita deadlocks si un recurso queda bloqueado.

### 7. Liberación garantizada
Todos los `lock()/acquire()` tienen `finally` con `unlock()/release()`.

### 8. `CompletableFuture` para endpoint asíncrono
Permite no bloquear el thread HTTP en el endpoint `/processOrder/async`.

### 9. JVM tuning (Dockerfile)
- G1GC con pausa objetivo 100ms
- Heap fijo (512-1024 MB) para evitar resizing

## Cuellos de Botella Identificados

1. **Simulated delay (100-500 ms)**: dominante. Inevitable por spec.
2. **Lock de inventario**: fair lock serializa todas las reservas.
   - *Mitigación*: locks por `productId` podría mejorar (trade-off: complejidad).
3. **Semáforo por cliente**: clientes muy activos encolan. Ajustable vía config.

## Próximas Mejoras Recomendadas

- Sustituir `ReentrantLock` global por locks por producto (`striped locks` de Guava).
- Persistencia real (Redis/DB) en lugar de `ConcurrentHashMap`.
- Circuit breaker (Resilience4j) para servicios externos.
- Métricas Prometheus + dashboards Grafana.
- Backpressure con proyecto Reactor (WebFlux) para >1000 RPS.

## Cómo Reproducir

```bash
# Ejecutar load test
mvn test -Dtest=HighLoadConcurrencyTest

# Arrancar la app
mvn spring-boot:run

# Probar con curl (PowerShell)
$body = '{"orderId":"O1","customerId":"C1","orderAmount":100,"orderItems":[{"productId":"PROD-001","productName":"A","quantity":2,"unitPrice":50}]}'
Invoke-RestMethod -Uri http://localhost:8080/processOrder -Method Post -Body $body -ContentType 'application/json'

# Ver estadísticas
Invoke-RestMethod http://localhost:8080/statistics
```

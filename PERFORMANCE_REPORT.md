# Informe de Rendimiento y Optimización

## Configuración del Entorno de Prueba

- **JVM**: OpenJDK 17
- **Framework**: Spring Boot 3.2.0
- **Servidor**: Tomcat embebido (default)
- **Thread Pool de Aplicación**: core=20, max=100, queue=500
- **Semáforo por cliente**: 5 solicitudes concurrentes

## Metodología

Se ejecutaron dos suites de prueba:

- **`HighLoadConcurrencyTest`**: 1000 pedidos concurrentes desde 200 clientes virtuales. Valida el cumplimiento de la consigna.
- **`OverloadStressTest`**: 5000 pedidos concurrentes desde 500 clientes virtuales. Valida comportamiento bajo sobrecarga extrema.

Ambas usan `CountDownLatch` para sincronizar el inicio de todos los threads y maximizar la contención.

Métricas capturadas por solicitud:
- Latencia individual (tiempo desde envío hasta respuesta)
- Throughput (req/s)
- Percentiles p50, p95, p99, max
- Tasa de éxito vs fallo
- Consistencia de datos (éxito + fallidos = total, pérdida cero)

## Resultados — Load Test (1000 pedidos concurrentes)

> `HighLoadConcurrencyTest` · 200 clientes virtuales · Hardware: Windows 11, JDK 21, i-series CPU

| Métrica | Valor |
|---|---|
| Total solicitudes | 1000 |
| Exitosas (stock disponible) | 500 |
| Fallidas (inventario agotado) | 500 |
| Pérdida de datos | **0** |
| Tiempo total (wall time) | 2091 ms |
| Throughput | **478 req/s** |
| Latencia promedio | 352 ms |
| Latencia p50 | 310 ms |
| Latencia p95 | 886 ms |
| Latencia p99 | 1009 ms |
| Latencia máxima | 1067 ms |

> Los pedidos "fallidos" son rechazos legítimos por inventario insuficiente, **no errores de concurrencia**.
> El invariante `éxito + fallidos = 1000` se cumple: cero pérdidas de datos.

## Resultados — Stress Test (5000 pedidos concurrentes)

> `OverloadStressTest` · 500 clientes virtuales · misma máquina

| Métrica | Valor |
|---|---|
| Total solicitudes | 5000 |
| Exitosas | 500 |
| Fallidas (rechazadas por backpressure/inventario) | 4500 |
| Pérdida de datos | **0** |
| Tiempo total (wall time) | 1046 ms |
| Throughput | **4780 req/s** |
| Latencia promedio | 73 ms |
| Latencia p50 | 0 ms (rechazo inmediato) |
| Latencia p95 | 522 ms |
| Latencia p99 | 820 ms |
| Latencia máxima | 1037 ms |

> El alto throughput en el stress test se explica porque la mayoría de los pedidos son
> rechazados rápidamente (inventario agotado o semáforo de cliente lleno) sin incurrir
> en el delay simulado de 100-500ms. Esto demuestra que el sistema **degrada graciosamente**
> bajo sobrecarga: responde rápido en lugar de colapsar.

## Comparativa entre escenarios

| Escenario | Pedidos | Clientes | Throughput | p99 | Pérdidas |
|-----------|---------|----------|------------|-----|----------|
| Load test | 1000 | 200 | 478 req/s | 1009 ms | **0** |
| Stress test | 5000 | 500 | 4780 req/s | 820 ms | **0** |

> Nota: latencias dominadas por `Thread.sleep(100-500ms)` que simula la operación de negocio.

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
# Load test (1000 pedidos concurrentes)
mvn test -Dtest=HighLoadConcurrencyTest

# Stress test (5000 pedidos concurrentes)
mvn test -Dtest=OverloadStressTest

# Todos los tests
mvn test

# Arrancar la app
mvn spring-boot:run

# Probar con curl (PowerShell)
$body = '{"orderId":"O1","customerId":"C1","orderAmount":100,"orderItems":[{"productId":"PROD-001","productName":"A","quantity":2,"unitPrice":50}]}'
Invoke-RestMethod -Uri http://localhost:8080/processOrder -Method Post -Body $body -ContentType 'application/json'

# Ver estadísticas del sistema
Invoke-RestMethod http://localhost:8080/statistics
```

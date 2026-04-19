# java-challenge-baufest

API REST de alta concurrencia para procesamiento de pedidos, desarrollada con Spring Boot 3 y Java 17.

> Challenge técnico: sistema capaz de manejar hasta 1000 solicitudes concurrentes sin pérdida de datos ni inconsistencias, evitando condiciones de carrera, deadlocks y uso ineficiente de recursos.

## Características

- **Procesamiento Concurrente**: Manejo eficiente de múltiples solicitudes simultáneas
- **Thread Safety**: Uso de `ConcurrentHashMap`, `AtomicLong`, `ReentrantLock` y `Semaphore`
- **Prevención de Condiciones de Carrera**: Bloqueos justos y control de inventario atómico
- **Prevención de Deadlocks**: Timeouts en adquisición de semáforos y locks
- **Procesamiento Asíncrono**: Soporte para procesamiento asíncrono con `CompletableFuture`
- **Idempotencia**: Manejo de pedidos duplicados

## Arquitectura de Concurrencia

### Mecanismos de Seguridad

| Mecanismo | Uso | Ubicación |
|-----------|-----|-----------|
| `ConcurrentHashMap` | Almacenamiento thread-safe de pedidos | `OrderProcessingService` |
| `AtomicLong` | Estadísticas atómicas | `OrderProcessingService` |
| `ReentrantLock` (fair) | Control de inventario | `OrderProcessingService.reserveInventory()` |
| `Semaphore` (per customer) | Limitar concurrentes por cliente | `customerSemaphores` |
| `CallerRunsPolicy` | Manejo de sobrecarga | `AsyncConfig` |

### Endpoints

#### Procesar Pedido (Síncrono)
```
POST /processOrder
Content-Type: application/json

{
  "orderId": "ORDER-001",
  "customerId": "CUST-001",
  "orderAmount": 150.00,
  "orderItems": [
    {
      "productId": "PROD-001",
      "productName": "Product A",
      "quantity": 2,
      "unitPrice": 50.00
    },
    {
      "productId": "PROD-002",
      "productName": "Product B",
      "quantity": 1,
      "unitPrice": 50.00
    }
  ]
}
```

#### Procesar Pedido (Asíncrono)
```
POST /processOrder/async
```

#### Consultar Estado
```
GET /orders/{orderId}
```

#### Estadísticas del Sistema
```
GET /statistics
```

## Configuración

Editar `application.properties`:

```properties
# Thread Pool
app.thread-pool.core-size=20
app.thread-pool.max-size=100
app.thread-pool.queue-capacity=500

# Order Processing
app.order.processing-timeout-ms=5000
app.order.max-concurrent-per-customer=5
```

## Prerequisitos

- Java 17 o superior (probado con JDK 21)
- Maven 3.8+
- Docker (opcional)

> **Importante:** Maven debe usar JDK 17+ (no JRE, no JDK 8).
> Verificar con `mvn -version` que muestre `Java version: 17` o superior.
> Si muestra una versión anterior, configurar `JAVA_HOME` antes de ejecutar:
> ```bash
> # Linux / macOS
> export JAVA_HOME=/path/to/jdk-21
> # Windows (PowerShell)
> $env:JAVA_HOME="C:\Program Files\Java\jdk-21"
> $env:Path="$env:JAVA_HOME\bin;$env:Path"
> ```

## Ejecución

### Local con Maven
```bash
# Compilar y ejecutar
mvn spring-boot:run

# O bien: compilar el JAR y ejecutarlo directamente
mvn clean package -DskipTests
java -jar target/java-challenge-baufest-1.0.0.jar
```

La API queda disponible en `http://localhost:8080`.

### Docker
```bash
docker build -t order-processing-api .
docker run -p 8080:8080 order-processing-api
```

### Tests
```bash
# Todos los tests (unitarios + integración + load + stress)
mvn test

# Solo unitarios e integración (rápido, ~10s)
mvn test -Dtest="OrderProcessingServiceTest,OrderControllerConcurrencyTest"

# Load test (1000 requests concurrentes, ~3s)
mvn test -Dtest=HighLoadConcurrencyTest

# Stress test (5000 requests concurrentes, ~2s)
mvn test -Dtest=OverloadStressTest
```

## Endpoints

| Método | Path | Descripción |
|--------|------|-------------|
| POST | `/processOrder` | Procesamiento síncrono (spec del challenge) |
| POST | `/processOrder/async` | Procesamiento asíncrono con `CompletableFuture` |
| GET  | `/orders/{orderId}` | Consultar estado de un pedido |
| GET  | `/statistics` | Métricas del sistema |
| GET  | `/health` | Health check |
| GET  | `/actuator/metrics` | Métricas Spring Actuator |

## Pruebas de Concurrencia

Tres niveles de pruebas:

| Test | Tipo | Pedidos | Objetivo |
|------|------|---------|----------|
| `OrderProcessingServiceTest` | Unitario | 1-10 | Lógica de negocio y reserva de inventario |
| `OrderControllerConcurrencyTest` | Integración (MockMvc) | 50 | Wiring HTTP + idempotencia bajo concurrencia |
| `HighLoadConcurrencyTest` | Load test | 1000 | Cumplimiento de la consigna (1000 concurrentes) |
| `OverloadStressTest` | Stress test | 5000 | Comportamiento bajo sobrecarga extrema |

```bash
# Load test (1000 concurrentes)
mvn test -Dtest=HighLoadConcurrencyTest

# Stress test (5000 concurrentes - verifica backpressure)
mvn test -Dtest=OverloadStressTest
```

Ambos imprimen un informe con throughput y percentiles (p50/p95/p99).

## Prevención de Problemas

### Condiciones de Carrera
- `putIfAbsent` para evitar sobrescrituras
- Locks explícitos para operaciones de inventario
- Variables atómicas para contadores

### Deadlocks
- Timeouts en semáforos (5 segundos por defecto)
- Locks con orden de adquisición consistente
- Liberación garantizada en bloques `finally`

### Sobrecarga de Recursos
- Pool de threads limitado (max 100)
- Cola de espera con capacidad definida
- Semáforo por cliente (max 5 concurrentes)
- `CallerRunsPolicy` para rechazar políticas

# Próximos Passos - WORM Performance Optimization

**Data**: 19 de Março de 2026
**Status**: Fases 1-3 Completas ✅ | Fases 4-5 Pendentes ⏳

---

## 📋 Resumo de Progresso

| Fase | Título | Status | Impacto Estimado |
|------|--------|--------|------------------|
| 1 | Remove Read-Before-Write em `save()` | ✅ Completo | +20-30% em `save()` |
| 2 | Reduzir Regex/Allocation em QueryBuilder | ✅ Completo | +15-40% em query building |
| 3 | Fast-Paths em EntityMapper/Persister | ✅ Completo | +5-15% em mapeamento |
| 4 | Connection Pooling & Statement Cache | ⏳ Pendente | +10-20% em batch |
| 5 | Lazy MethodHandle Initialization | ⏳ Pendente | +5-15% global |
| 6 | Benchmark Parity com JPA | ⏳ Pendente | Meta: WORM > JPA |

---

## 🎯 Metas de Benchmark Atuais (vs. JPA)

### Baseline (Antes das Otimizações)

| Scenario | WORM (us/op) | JPA (us/op) | WORM vs JPA | Target |
|----------|--------------|------------|------------|--------|
| selectById | 4370.34 | 2112.11 | **-2.07x** ❌ | ≤ 2112.11 |
| selectCountByStatus | 2387.43 | 1263.20 | **-1.89x** ❌ | ≤ 1263.20 |
| selectPageByAuthor | 4822.72 | 3237.65 | **-1.49x** ❌ | ≤ 3237.65 |
| selectJoinBookAuthor | 2683.22 | 2851.57 | **+0.06x** ✅ | ≤ 2851.57 |
| insertSingle | 3152.05 | 1866.94 | **-1.69x** ❌ | ≤ 1866.94 |
| insertBatch | 13174.98 | 6631.87 | **-1.99x** ❌ | ≤ 6631.87 |
| updateSingle | 4089.79 | 1743.80 | **-2.35x** ❌ | ≤ 1743.80 |
| updateBatch | 14622.62 | 6288.26 | **-2.33x** ❌ | ≤ 6288.26 |
| deleteSingle | 2021.06 | 4839.87 | **+0.42x** ✅ | ≤ 4839.87 |
| deleteBatch | 18386.36 | 3000.69 | **-6.13x** ❌ | ≤ 3000.69 |

**Situação Atual**: 2 de 10 cenários em parity | **Target**: 10 de 10 até Fase 5

---

## 🔧 Fase 4: Connection Pooling & Statement Cache

### 4.1 Problem Statement
- Cada operação batch requer nova conexão do pool
- Prepared statements não são reutilizados entre chamadas
- Overhead de criação de PreparedStatement em cada batch
- AutoCommit mutations causam latência em algumas configs

### 4.2 Implementação Proposta

#### 4.2.1 Statement Cache por SQL Template
**Arquivo**: `src/main/java/br/com/liviacare/worm/orm/sql/StatementCache.java` (novo)

```java
public class StatementCache {
    private final Map<String, SoftReference<PreparedStatement>> cache
        = new ConcurrentHashMap<>();
    private final javax.sql.DataSource dataSource;

    public PreparedStatement getPreparedStatement(
        java.sql.Connection conn, String sql) throws SQLException {
        // Return cached statement or create new one
    }

    public void clear() {
        cache.clear();
    }
}
```

**Ganho**: ~5-10% redução em overhead de statement creation
**Risco**: Memory leak se statements não forem fechados corretamente

#### 4.2.2 Otimizar Batch Connection Reuse
**Arquivo**: `src/main/java/br/com/liviacare/worm/orm/sql/SqlExecutor.java`

```java
public int[] executeBatchOptimized(String sql, List<Object[]> batchParams,
                                    java.sql.Connection conn) {
    // Reuse connection passed in, don't create new one per batch
    // Use same statement for all params in batch
}
```

**Ganho**: +15-20% em throughput de batch
**Risco**: Requires caller to manage connection lifecycle

#### 4.2.3 Connection Pool Tuning
**Arquivo**: `src/main/resources/application.yaml`

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      max-lifetime: 1800000
      idle-timeout: 600000
      connection-timeout: 30000

worm:
  batch-size: 1000
  statement-cache-enabled: true
  statement-cache-size: 100
```

**Ganho**: +5-10% em latência de batch
**Prioridade**: ALTA (impacta todos os cenários batch)

---

## 🚀 Fase 5: Lazy MethodHandle Initialization

### 5.1 Problem Statement
- MethodHandles são criados/invocados por operação
- EntityMetadata é construída completa mesmo para queries simples
- Reflexão repetida para getters/setters em tight loops

### 5.2 Implementação Proposta

#### 5.2.1 MethodHandle Pool & LRU Cache
**Arquivo**: `src/main/java/br/com/liviacare/worm/orm/registry/MethodHandlePool.java` (novo)

```java
public class MethodHandlePool {
    private static final int MAX_CACHED_HANDLES = 10000;
    private final Map<String, MethodHandle> cache
        = Collections.synchronizedMap(
            new LinkedHashMap<String, MethodHandle>(MAX_CACHED_HANDLES, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
                    return size() > MAX_CACHED_HANDLES;
                }
            }
        );

    public MethodHandle getOrCreate(String key, Supplier<MethodHandle> factory) {
        return cache.computeIfAbsent(key, k -> factory.get());
    }
}
```

**Ganho**: ~5-8% redução em overhead de MethodHandle creation
**Risco**: Concurrency complexity, memory usage

#### 5.2.2 Lazy EntityMetadata Population
**Arquivo**: `src/main/java/br/com/liviacare/worm/orm/registry/EntityMetadata.java`

Adicionar lazy initialization para fields menos comuns:
```java
public class EntityMetadata<T> {
    // ... existing fields ...

    private volatile MethodHandle[] cachedJoinSetters;

    public MethodHandle[] getJoinSetters() {
        if (cachedJoinSetters == null) {
            synchronized(this) {
                if (cachedJoinSetters == null) {
                    cachedJoinSetters = buildJoinSetters();
                }
            }
        }
        return cachedJoinSetters;
    }
}
```

**Ganho**: +3-5% em latência de inicialização
**Risco**: Double-checked locking complexidade

#### 5.2.3 Specialized Converters Cache
**Arquivo**: `src/main/java/br/com/liviacare/worm/orm/converter/ConverterRegistry.java`

```java
public class ConverterRegistry {
    private final Map<Class<?>, ColumnConverter> typeCache
        = new ConcurrentHashMap<>();

    public ColumnConverter getConverter(Class<?> type) {
        return typeCache.computeIfAbsent(type, this::buildConverter);
    }

    private ColumnConverter buildConverter(Class<?> type) {
        // Build once, reuse forever
    }
}
```

**Ganho**: +2-5% em operações de conversão de tipo
**Prioridade**: MÉDIA (impacto menor que Statement Cache)

---

## 📊 Fase 6: Benchmark Parity Strategy

### 6.1 Análise de Gargalos Específicos

#### 6.1.1 `selectById` - WORM 2.07x mais lento

**Causas Prováveis**:
1. Overhead de EntityMetadata lookup
2. Conversão de tipos (UUID, Instant, etc.)
3. Reflexão em EntityMapper.mapRow()

**Ações Recomendadas**:
- [ ] Profile com JFR para confirmar hotspot
- [ ] Implementar ID-only fast-path (sem conversão)
- [ ] Cache EntityMetadata por classe (já existe, validar overhead)

**Código Proposto**:
```java
public <T, I> Optional<T> findByIdFastPath(Class<T> clazz, I id) {
    // Skip joins, skip converters, direct mapping
    // Ideal para queries simples de ID
}
```

#### 6.1.2 `insertBatch` / `updateBatch` - WORM 2x mais lento

**Causas Prováveis**:
1. Batch executado per-row (não em batch real)
2. Connection churn
3. Prepared statement recreation

**Ações Recomendadas**:
- [ ] Implementar Fase 4 (Statement Cache)
- [ ] Validar que batch usa PreparedStatement.addBatch()
- [ ] Profile batch execution path

#### 6.1.3 `deleteBatch` - WORM 6.13x mais lento ⚠️

**Causas Prováveis**:
1. Soft-delete pode estar disparando UPDATE em vez de DELETE
2. Reseed entre rounds pode estar lento
3. Per-row delete em vez de batch

**Ações Recomendadas**:
- [ ] Verificar se soft-delete está habilitado no benchmark
- [ ] Implementar batch DELETE puro (sem soft-delete wrapping)
- [ ] Profile deleteBatch scenario

---

## 📋 Checklist de Implementação

### Fase 4 (Priority: HIGH)
- [ ] Criar `StatementCache` com LRU eviction
- [ ] Adicionar `statement-cache-enabled` config
- [ ] Refator `SqlExecutor.executeBatch()` para statement reuse
- [ ] Teste: benchmark batch com cache vs. sem cache
- [ ] Validar: sem memory leaks (GC logs)

### Fase 5 (Priority: MEDIUM)
- [ ] Criar `MethodHandlePool` com sincronização
- [ ] Implementar lazy loading em `EntityMetadata`
- [ ] Cache especializado em `ConverterRegistry`
- [ ] Teste: memory profile (heap usage)
- [ ] Teste: thread-safety stress test

### Fase 6 (Priority: HIGH)
- [ ] Re-rodar benchmark completo
- [ ] Comparar vs. baseline
- [ ] Profile hotspots com JFR
- [ ] Implementar fast-paths específicos por cenário
- [ ] Gerar relatório final vs. JPA

---

## 🧪 Teste & Validação

### Benchmark Command (Full Suite)

```bash
cd /home/toni/IdeaProjects/Livia/worm

# Fast mode (para CI/CD)
java -Dbenchmark.mode=fast \
     -Dbenchmark.warmupRounds=2 \
     -Dbenchmark.measureRounds=3 \
     -Dbenchmark.operationsPerRound=100 \
     -cp target/classes:target/test-classes \
     org.springframework.boot.test.context.SpringBootTest \
     br.com.worm.demo.WormVsJpaBenchmarkTests

# Full mode (para análise profunda)
java -Dbenchmark.mode=full \
     -Dbenchmark.warmupRounds=4 \
     -Dbenchmark.measureRounds=10 \
     -Dbenchmark.operationsPerRound=700 \
     -cp target/classes:target/test-classes \
     org.springframework.boot.test.context.SpringBootTest \
     br.com.worm.demo.WormVsJpaBenchmarkTests
```

### Profiling Command (JFR)

```bash
java -XX:+UnlockCommercialFeatures \
     -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=30s,filename=worm-profile.jfr \
     -cp target/classes:target/test-classes \
     org.springframework.boot.test.context.SpringBootTest \
     br.com.worm.demo.WormVsJpaBenchmarkTests

# Analyze with:
jfr print worm-profile.jfr | grep -A5 "Method Stack"
```

---

## 📈 Métricas de Sucesso

### Critérios de Aceitação

#### Fase 4 ✅
- [ ] Statement cache hit rate > 80%
- [ ] Batch throughput +15% vs. baseline
- [ ] Memory overhead < 50MB
- [ ] Zero memory leaks após GC

#### Fase 5 ✅
- [ ] MethodHandle cache hit rate > 90%
- [ ] Startup time < 5% slower
- [ ] Memory overhead < 100MB total
- [ ] Thread-safety verified with stress test

#### Fase 6 ✅
- [ ] `selectById`: WORM latency ≤ 2500 us/op (vs. 2112 JPA)
- [ ] `insertBatch`: WORM throughput ≥ 100 ops/s (vs. 150.79 JPA)
- [ ] `updateBatch`: WORM throughput ≥ 100 ops/s (vs. 159.03 JPA)
- [ ] `deleteBatch`: WORM throughput ≥ 100 ops/s (vs. 333.26 JPA)
- [ ] 6+ cenários em parity com JPA

---

## 🗓️ Timeline Sugerido

| Fase | Duração Est. | Prioridade | Critério Release |
|------|-------------|-----------|------------------|
| 4 | 2-3 dias | 🔴 HIGH | + 15% batch throughput |
| 5 | 3-4 dias | 🟡 MEDIUM | + 5% global latency |
| 6 | 2-3 dias | 🔴 HIGH | 6+ cenários vs JPA |

**Timeline Total**: ~7-10 dias para atingir parity com JPA

---

## 📚 Referências

- [IMPLEMENTATION_LOG_v1.md](IMPLEMENTATION_LOG_v1.md) - Fases 1-3
- [plan-wormMaisRapidoQueJpa.prompt.md](plan-wormMaisRapidoQueJpa.prompt.md) - Plano original
- JDBC Best Practices: Connection pooling, prepared statements
- JDK Flight Recorder documentation

---

## 💡 Observações Adicionais

### O que Não Fazer ❌
- Remover soft-delete para artificialmente melhorar `deleteBatch`
- Sacrificar segurança thread-safety por performance
- Implementar micro-otimizações sem profiling

### O que Fazer ✅
- Sempre medir com profiling antes/depois
- Manter testes passando (suite atual)
- Documentar trade-offs (memory vs. speed)
- Considerar casos de uso reais (não só benchmark)

---

**Última atualização**: 19/03/2026
**Responsável**: WORM Team
**Status**: ⏳ Awaiting Approval para Fase 4


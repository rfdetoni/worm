## Plan: WORM mais rápido que JPA

Vamos atacar primeiro os maiores gargalos estruturais que hoje penalizam o WORM no benchmark (queries extras em `save`, batch com overhead de conexão/reflexão, e custo de construção SQL/mapeamento por operação), depois consolidar uma suíte de benchmark confiável e um plano permanente em TXT. O objetivo é reduzir latência por operação, aumentar throughput e manter previsibilidade, com metas mensuráveis por cenário (`select`, `insert`, `update`, `delete`, batch).

### Steps
1. Definir benchmark justo e reprodutível com cenários equivalentes em [README.md](README.md) e [pom.xml](pom.xml), formalizando warmup, rounds e dataset.
2. Remover query extra de existência em `save` revisando `OrmManager.save` e `OrmManager.existsById` em [src/main/java/br/com/liviacare/worm/orm/OrmManager.java](src/main/java/br/com/liviacare/worm/orm/OrmManager.java).
3. Reescrever caminho batch para reutilizar conexão/statement sem reflexão por chamada em [src/main/java/br/com/liviacare/worm/orm/sql/SqlExecutor.java](src/main/java/br/com/liviacare/worm/orm/sql/SqlExecutor.java).
4. Reduzir alocações e regex dinâmicas no `QueryBuilder`/`FilterBuilder` em [src/main/java/br/com/liviacare/worm/orm/sql/QueryBuilder.java](src/main/java/br/com/liviacare/worm/orm/sql/QueryBuilder.java) e [src/main/java/br/com/liviacare/worm/query/FilterBuilder.java](src/main/java/br/com/liviacare/worm/query/FilterBuilder.java).
5. Otimizar mapeamento e persistência (`EntityMapper`, `EntityPersister`) com fast-paths sem reflexão repetida em [src/main/java/br/com/liviacare/worm/orm/mapping/EntityMapper.java](src/main/java/br/com/liviacare/worm/orm/mapping/EntityMapper.java) e [src/main/java/br/com/liviacare/worm/orm/mapping/EntityPersister.java](src/main/java/br/com/liviacare/worm/orm/mapping/EntityPersister.java).
6. Criar e versionar plano detalhado em `benchmark/WORM_PERFORMANCE_PLAN.txt` com backlog priorizado, metas por cenário e critérios de aceitação.

### Further Considerations
1. `save` deve ser: Option A estrito (`insert`/`update` explícitos), Option B `upsert` configurável, Option C manter compatibilidade atual.
2. Benchmark oficial: Option A JMH + Testcontainers, Option B integração Spring atual com controles estatísticos reforçados.
3. Este rascunho atende? Se sim, detalho a estrutura final do `WORM_PERFORMANCE_PLAN.txt` para execução faseada.


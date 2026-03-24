package br.com.liviacare.worm.example;

import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.annotation.query.QueryRepository;

import java.util.List;

@QueryRepository
public interface SampleQueryRepository {

    @Query("SELECT 'x' as value")
    List<String> findValues();
}


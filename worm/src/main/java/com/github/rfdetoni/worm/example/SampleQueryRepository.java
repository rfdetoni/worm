package com.github.rfdetoni.worm.example;

import com.github.rfdetoni.worm.annotation.query.Query;
import com.github.rfdetoni.worm.annotation.query.QueryRepository;

import java.util.List;

@QueryRepository
public interface SampleQueryRepository {

    @Query("SELECT 'x' as value")
    List<String> findValues();
}


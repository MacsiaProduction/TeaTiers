package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.ImportRun
import org.springframework.data.jpa.repository.JpaRepository

interface ImportRunRepository : JpaRepository<ImportRun, Long>

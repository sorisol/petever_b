package com.petever.api.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity @Table(name = "sync_runs")
public class SyncRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String source;
    public String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_scope", columnDefinition = "jsonb") public String requestScope;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "finished_at") public Instant finishedAt;
    @Column(name = "last_successful_page") public Integer lastSuccessfulPage;
    @Column(name = "fetched_count") public int fetchedCount;
    @Column(name = "inserted_count") public int insertedCount;
    @Column(name = "updated_count") public int updatedCount;
    @Column(name = "failed_count") public int failedCount;
    @Column(name = "error_summary", columnDefinition = "text") public String errorSummary;
}

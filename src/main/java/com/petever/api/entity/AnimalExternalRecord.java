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

@Entity @Table(name = "animal_external_records")
public class AnimalExternalRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "animal_id") public Animal animal;
    public String source;
    @Column(name = "external_id") public String externalId;
    @Column(name = "notice_number") public String noticeNumber;
    @Column(name = "notice_start_date") public LocalDate noticeStartDate;
    @Column(name = "notice_end_date") public LocalDate noticeEndDate;
    @Column(name = "external_status") public String externalStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_payload", columnDefinition = "jsonb") public String rawPayload;
    @Column(name = "last_seen_at") public Instant lastSeenAt;
    @Column(name = "last_synced_at") public Instant lastSyncedAt;
}

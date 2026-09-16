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

@Entity @Table(name = "shelters")
public class Shelter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "external_source") public String externalSource;
    @Column(name = "external_id") public String externalId;
    public String name;
    public String phone;
    public String address;
    @Column(name = "region_code") public String regionCode;
    @Column(name = "participation_status") public String participationStatus = "UNREGISTERED";
    @Column(name = "last_synced_at") public Instant lastSyncedAt;
}

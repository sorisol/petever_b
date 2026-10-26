package com.petever.api.entity;

import java.time.Instant;
import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "veterinary_clinics")
public class VeterinaryClinic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "external_source") public String externalSource;
    @Column(name = "external_id") public String externalId;
    public String name;
    public String phone;
    public String address;
    @Column(name = "region_code") public String regionCode;
    @Column(precision = 10, scale = 7) public BigDecimal latitude;
    @Column(precision = 10, scale = 7) public BigDecimal longitude;
    @Column(name = "website_url") public String websiteUrl;
    @Column(name = "operating_status") public String operatingStatus;
    @Column(name = "last_verified_at") public Instant lastVerifiedAt;
}

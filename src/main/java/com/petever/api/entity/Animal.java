package com.petever.api.entity;

import java.math.BigDecimal;
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

@Entity @Table(name = "animals")
public class Animal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "shelter_id") public Shelter shelter;
    public String origin = "PUBLIC_API";
    public String name;
    @Column(name = "description", columnDefinition = "text") public String description;
    public String species = "UNKNOWN";
    @Column(name = "breed_name") public String breedName;
    public String sex = "UNKNOWN";
    @Column(name = "neuter_status") public String neuterStatus = "UNKNOWN";
    @Column(name = "age_description") public String ageDescription;
    @Column(name = "weight_kg", precision = 6, scale = 2) public BigDecimal weightKg;
    public String color;
    @Column(name = "found_date") public LocalDate foundDate;
    @Column(name = "found_place") public String foundPlace;
    @Column(name = "care_status") public String careStatus = "UNKNOWN";
    @Column(name = "status_authority") public String statusAuthority = "PUBLIC_API";
    public String visibility = "PUBLIC";
    @Column(name = "consultation_enabled") public boolean consultationEnabled;
    @OneToMany(mappedBy = "animal") @OrderBy("sortOrder ASC, id ASC") public List<AnimalImage> images = new ArrayList<>();
}

package com.kowalchuk.quoteservice.submission;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A 2022 NAICS class and whether we write it. Reference data owned by Flyway migrations.
 */
@Entity
@Immutable
@Table(name = "naics_classes")
public class NaicsClass {

	@Id
	private String code;

	@Column(nullable = false)
	private String title;

	@Column(name = "in_appetite", nullable = false)
	private boolean inAppetite;

	protected NaicsClass() {
	}

	public NaicsClass(String code, String title, boolean inAppetite) {
		this.code = code;
		this.title = title;
		this.inAppetite = inAppetite;
	}

	public String getCode() {
		return code;
	}

	public String getTitle() {
		return title;
	}

	public boolean isInAppetite() {
		return inAppetite;
	}

}

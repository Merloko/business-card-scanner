package com.businesscard.scanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "business_cards")
data class BusinessCard(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personName: String = "",
    val companyName: String = "",
    val jobTitle: String = "",
    val phone: String = "",
    val mobile: String = "",
    val email: String = "",
    val website: String = "",
    val address: String = "",
    val rawTextFront: String = "",
    val rawTextBack: String = "",
    val frontImagePath: String = "",
    val backImagePath: String = "",
    val notes: String = "",
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

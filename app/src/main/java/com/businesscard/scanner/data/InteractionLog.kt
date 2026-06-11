package com.businesscard.scanner.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "interaction_log",
    foreignKeys = [ForeignKey(
        entity = BusinessCard::class,
        parentColumns = ["id"],
        childColumns = ["cardId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("cardId")]
)
data class InteractionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val note: String = ""
)

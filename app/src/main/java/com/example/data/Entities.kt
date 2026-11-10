package com.example.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#1D4ED8",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId"), Index("updatedAt")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val folderId: Long? = null,
    val scanMode: String = "DOCUMENT",
    val pageCount: Int = 1,
    val coverThumbnailPath: String = "",
    val combinedOcrText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val pageIndex: Int,
    val originalImagePath: String,
    val processedImagePath: String,
    val thumbnailPath: String,
    val quadSerialized: String,
    val wasBoundaryAutoDetected: Boolean,
    val rotationDegrees: Float,
    val filterType: String,
    val brightness: Float,
    val contrast: Float,
    val threshold: Float,
    val sharpness: Float,
    val ocrText: String = "",
    val ocrLanguage: String = "ENGLISH"
)

data class DocumentWithPages(
    @Embedded val document: DocumentEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "documentId"
    )
    val pages: List<PageEntity>
) {
    val orderedPages: List<PageEntity>
        get() = pages.sortedBy { it.pageIndex }
}

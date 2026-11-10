package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocuSnapDao {

    // Folders
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeAllFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolderById(folderId: Long)

    // Documents
    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeAllDocuments(): Flow<List<DocumentEntity>>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    fun observeDocumentWithPages(documentId: Long): Flow<DocumentWithPages?>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    suspend fun getDocumentWithPagesOnce(documentId: Long): DocumentWithPages?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("UPDATE documents SET title = :newTitle, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun renameDocument(documentId: Long, newTitle: String, updatedAt: Long)

    @Query("UPDATE documents SET notes = :notes, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun updateDocumentNotes(documentId: Long, notes: String, updatedAt: Long)

    @Query("UPDATE documents SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun moveDocumentToFolder(documentId: Long, folderId: Long?, updatedAt: Long)

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocumentById(documentId: Long)

    // Pages
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    suspend fun getPagesForDocument(documentId: Long): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deletePagesForDocument(documentId: Long)
}

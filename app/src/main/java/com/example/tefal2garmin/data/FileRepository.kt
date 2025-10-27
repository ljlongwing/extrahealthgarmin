package com.example.tefal2garmin.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.tefal2garmin.utils.CsvReader
import com.example.tefal2garmin.utils.CsvValidator
import com.example.tefal2garmin.utils.RecordProcessor
import com.example.tefal2garmin.utils.GarminCsvGenerator
import com.example.tefal2garmin.utils.GarminCsvSchema
import com.example.tefal2garmin.utils.ValidationReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FileRepository(
    private val contentResolver: ContentResolver,
    private val csvReader: CsvReader,
    private val csvValidator: CsvValidator,
    private val context: Context
) {
    
    companion object {
        private const val TAG = "FileRepository"
    }
    
    suspend fun getFileInfo(uri: Uri): FileInfo = withContext(Dispatchers.IO) {
        val fileName = getFileName(uri)
        val fileSize = getFileSize(uri)
        FileInfo(fileName, fileSize)
    }
    
    suspend fun processCsvFile(
        uri: Uri, 
        onProgress: (String) -> Unit
    ): Result<CsvProcessingResult> = withContext(Dispatchers.IO) {
        try {
            onProgress("Validating encoding...")
            
            // Validate encoding first
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val encodingResult = csvValidator.validateEncoding(inputStream)
                if (!encodingResult.isValid) {
                    return@withContext Result.failure(
                        Exception(encodingResult.errorMessage ?: "Invalid encoding")
                    )
                }
            }

            onProgress("Reading CSV...")
            val lines = csvReader.readCsvFile(uri)
            if (lines.isEmpty()) {
                return@withContext Result.failure(Exception("CSV file is empty"))
            }

            onProgress("Validating format...")
            val delimiterResult = csvValidator.validateDelimiter(lines)
            if (!delimiterResult.isValid) {
                return@withContext Result.failure(
                    Exception(delimiterResult.errorMessage ?: "Invalid delimiter")
                )
            }

            onProgress("Validating headers...")
            val headerLine = lines[0]
            val headerResult = csvValidator.validateHeaders(headerLine)
            if (!headerResult.isValid) {
                return@withContext Result.failure(
                    Exception(headerResult.errorMessage ?: "Invalid headers")
                )
            }

            onProgress("Processing records...")
            val parsedData = csvReader.parseCsvLines(lines)
            val headers = parsedData.firstOrNull() ?: emptyList()

            // Pre-allocate with appropriate capacity for better memory management
            val processedDataList = ArrayList<Map<String, String?>>(lines.size - 1)
            val batchSize = 50 // Process in batches to manage memory

            parsedData.drop(1).chunked(batchSize).forEachIndexed { batchIndex, batch ->
                    onProgress("Processing records ${batchIndex * batchSize + 1}-${(batchIndex * batchSize + batch.size)}")
                    
                    batch.forEachIndexed { index, row ->
                        val recordMap = headers.zip(row).toMap()
                        onProgress("Processing record ${batchIndex * batchSize + index + 2}/${lines.size - 1}")
                        val result = RecordProcessor.processRecord(recordMap, csvValidator)
                        result.record?.let { processedRecord ->
                            processedDataList.add(processedRecord)
                        }
                    }
                }
                
                onProgress("Generating CSV...")
                val outputFileName = generateOutputFileName()
                val csvData = prepareCsvData(processedDataList) { current, total ->
                    onProgress("Generating file... $current/$total")
                }

            Result.success(
                CsvProcessingResult(
                    recordsProcessed = parsedData.size - 1, // Subtract header
                    recordsGenerated = processedDataList.size,
                    outputFile = null, // File will be saved via SAF
                    outputFileName = outputFileName,
                    csvData = csvData,
                    validationReport = null
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing CSV file", e)
            Result.failure(e)
        }
    }
    
    private fun getFileName(uri: Uri): String {
        var fileName = uri.lastPathSegment ?: "unknown_file.csv"
        
        try {
            @Suppress("DEPRECATION")
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve file name from ContentResolver", e)
        }
        
        return fileName
    }
    
    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        
        try {
            @Suppress("DEPRECATION")
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not retrieve file size from ContentResolver", e)
        }
        
        return size
    }
    
    private fun generateOutputFileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault()).format(Date())
        return "garmin-import-$timestamp.csv"
    }
    
    private fun prepareCsvData(
        records: List<Map<String, String?>>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): String {
        // Validate all records before writing
        val validationResults = records.map { GarminCsvSchema.validateRecord(it) }
        val invalidRecords = validationResults.filter { !it.isValid }
        
        if (invalidRecords.isNotEmpty()) {
            invalidRecords.forEachIndexed { index, result ->
                Log.w(TAG, "Invalid record ${index + 1}: ${result.issues.joinToString(", ")}")
            }
            throw Exception("Validation failed for ${invalidRecords.size} records")
        }
        
        // Build CSV content as string
        val csvContent = StringBuilder()
        
        // Write headers
        csvContent.append(GarminCsvSchema.generateHeader())
        csvContent.append("\n")

        // Write data records
        records.forEachIndexed { index, record ->
            if (index > 0) {
                csvContent.append("\n")
            }
            
            val csvLine = GarminCsvSchema.formatRecord(record)
            csvContent.append(csvLine)
            
            // Report progress
            onProgress(index + 1, records.size)
        }
        
        return csvContent.toString()
    }
}

data class FileInfo(
    val fileName: String,
    val fileSize: Long
)

data class CsvProcessingResult(
    val recordsProcessed: Int,
    val recordsGenerated: Int,
    val outputFile: File?,
    val outputFileName: String? = null,
    val csvData: String? = null,
    val validationReport: ValidationReport?
)
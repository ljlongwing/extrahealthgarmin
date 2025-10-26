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
            
            // Open input stream and validate encoding
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val encodingResult = csvValidator.validateEncoding(inputStream)
                if (!encodingResult.isValid) {
                    return@withContext Result.failure(
                        Exception(encodingResult.errorMessage ?: "Invalid encoding")
                    )
                }
                
                // Reset stream to read from beginning
                inputStream.reset()
                
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
                
                val processedDataList = parsedData.drop(1).mapIndexed { index, row ->
                    val recordMap = headers.zip(row).toMap()
                    onProgress("Processing record ${index + 1}/${parsedData.size - 1}")
                    val result = RecordProcessor.processRecord(recordMap, csvValidator)
                    result
                }
                
                val processedData = processedDataList.mapNotNull { result ->
                    if (result.success) result.record else null
                }
                
                onProgress("Generating CSV...")
                val outputFileName = generateOutputFileName()
                val result = GarminCsvGenerator.generateGarminCsv(processedData, outputFileName) { current, total ->
                    onProgress("Generating file... $current/$total")
                }
                
                if (result.success) {
                    Result.success(
                        CsvProcessingResult(
                            recordsProcessed = parsedData.size,
                            recordsGenerated = result.recordsGenerated,
                            outputFile = File("placeholder.csv"), // We'll handle file differently
                            validationReport = null // We'll handle validation differently
                        )
                    )
                } else {
                    Result.failure(Exception(result.error ?: "Unknown error occurred"))
                }
            } ?: Result.failure(Exception("Failed to open input stream"))
            
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
    
    private fun generateOutputFileName(): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault()).format(Date())
        val fileName = "garmin-import-$timestamp.csv"
        return File(context.getExternalFilesDir(null), fileName)
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
    val validationReport: ValidationReport?
)
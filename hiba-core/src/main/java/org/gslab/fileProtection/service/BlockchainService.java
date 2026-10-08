package org.gslab.fileProtection.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.gslab.fileProtection.exception.BlockchainNetworkException;
import org.gslab.fileProtection.exception.FileNotFoundException;
import org.gslab.fileProtection.model.FileMetadata;
import org.gslab.fileProtection.model.TransactionResult;

/**
 * 區塊鏈服務介面 定義與區塊鏈互動的方法
 */
public interface BlockchainService extends AutoCloseable {

    /**
     * 將檔案元資料上傳到區塊鏈
     *
     * @param metadata 檔案元資料
     * @return 區塊鏈交易雜湊
     * @throws Exception 上傳失敗時拋出異常
     */
    String uploadMetadata(FileMetadata metadata) throws Exception;

    /**
     * 批量上傳檔案元資料到區塊鏈
     *
     * @param metadataList 多個檔案元資料
     * @return 區塊鏈交易雜湊
     * @throws Exception 上傳失敗時拋出異常
     */
    String batchUploadMetadata(List<FileMetadata> metadataList) throws Exception;

    /**
     * 將檔案元資料上傳到區塊鏈並返回詳細交易資訊（包含 Gas 數據）
     *
     * <p>此方法提供完整的交易結果，包括：
     * <ul>
     *   <li>交易雜湊</li>
     *   <li>Gas 使用量（用於成本分析）</li>
     *   <li>Gas 價格（用於成本計算）</li>
     *   <li>區塊號碼（用於確認追蹤）</li>
     *   <li>交易狀態（成功/失敗）</li>
     * </ul>
     *
     * @param metadata 檔案元資料
     * @return TransactionResult 包含完整交易資訊
     * @throws Exception 上傳失敗時拋出異常
     */
    TransactionResult uploadMetadataWithReceipt(FileMetadata metadata) throws Exception;

    /**
     * 批量上傳檔案元資料到區塊鏈並返回詳細交易資訊（包含 Gas 數據）
     *
     * <p>此方法提供批量操作的完整交易結果，適用於性能測試和成本分析。
     *
     * @param metadataList 多個檔案元資料
     * @return TransactionResult 包含完整交易資訊
     * @throws Exception 上傳失敗時拋出異常
     */
    TransactionResult batchUploadMetadataWithReceipt(List<FileMetadata> metadataList) throws Exception;

    /**
     * 非同步將檔案元資料上傳到區塊鏈
     *
     * @param metadata 檔案元資料
     * @return 包含區塊鏈交易雜湊的 CompletableFuture
     * @throws CompletionException 非同步操作失敗時拋出異常(包含原始異常)
     */
    CompletableFuture<String> uploadMetadataAsync(FileMetadata metadata) throws CompletionException;

    /**
     * 非同步批量上傳檔案元資料到區塊鏈
     *
     * @param metadataList 多個檔案元資料
     * @return 包含區塊鏈交易雜湊的 CompletableFuture
     * @throws CompletionException 非同步操作失敗時拋出異常(包含原始異常)
     */
    CompletableFuture<String> batchUploadMetadataAsync(List<FileMetadata> metadataList) throws CompletionException;

    /**
     * 從區塊鏈檢索元資料
     *
     * @param txHash 交易雜湊
     * @return 檔案元資料
     * @throws Exception 檢索失敗時拋出異常
     */
    FileMetadata retrieveMetadata(String txHash) throws Exception;

    /**
     * 根據檔案雜湊從區塊鏈檢索元資料
     *
     * @param fileHash 檔案雜湊
     * @return 檔案元資料
     * @throws FileNotFoundException      當檔案在區塊鏈上不存在時拋出
     * @throws BlockchainNetworkException 當發生網路或系統錯誤時拋出
     */
    FileMetadata retrieveMetadataByFileHash(String fileHash) throws FileNotFoundException, BlockchainNetworkException;

    /**
     * 驗證區塊鏈上的資料完整性
     *
     * @param txHash       交易雜湊
     * @param expectedHash 預期的檔案雜湊
     * @return 驗證結果
     */
    boolean verifyIntegrity(String txHash, String expectedHash);

    /**
     * 獲取交易狀態
     *
     * @param txHash 交易雜湊
     * @return 交易狀態
     */
    String getTransactionStatus(String txHash);

    /**
     * 檢查檔案是否已存在於區塊鏈上
     *
     * @param fileHash 檔案雜湊
     * @return 如果檔案已存在於區塊鏈上則回傳 true
     */
    boolean exists(String fileHash);

    /**
     * 檢查區塊鏈連線狀態
     *
     * @return 如果區塊鏈連線正常則回傳 true
     */
    boolean isConnected();

    /**
     * 關閉區塊鏈服務
     *
     * @throws Exception 關閉失敗時拋出異常
     */
    @Override
    void close() throws Exception;
}

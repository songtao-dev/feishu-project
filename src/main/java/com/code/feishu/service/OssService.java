package com.code.feishu.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 阿里云 OSS 上传/删除服务。
 *
 * 文件命名规则：diary/{type}/{yyyy/MM/dd}/{uuid}.{ext}
 *   - type: image / voice
 *   - 按日期分目录，避免单目录文件过多
 *   - UUID 防重名
 *
 * 上传成功返回完整公网 URL（public-read bucket 直接可访问）。
 */
@Service
public class OssService {

    @Autowired
    private OSS ossClient;

    @Value("${oss.bucket-name}")
    private String bucketName;

    @Value("${oss.public-domain}")
    private String publicDomain;

    /** 上传文件，返回完整公网URL */
    public String upload(MultipartFile file, String type) throws IOException {
        // 原始文件名取扩展名
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        // 按日期分目录
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        // UUID + 扩展名
        String objectKey = "diary/" + type + "/" + datePath + "/" + UUID.randomUUID().toString().replace("-", "") + ext;

        // 设置元数据
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (file.getContentType() != null) {
            metadata.setContentType(file.getContentType());
        }

        // 上传
        ossClient.putObject(bucketName, objectKey, file.getInputStream(), metadata);

        // 拼公网URL：去掉末尾斜杠 + / + objectKey
        String domain = publicDomain.endsWith("/") ? publicDomain.substring(0, publicDomain.length() - 1) : publicDomain;
        return domain + "/" + objectKey;
    }

    /** 删除文件（软删除时异步清理用，或硬删除媒体时调用） */
    public void delete(String url) {
        if (url == null || url.isEmpty()) return;
        // 从 URL 提取 objectKey：去掉域名前缀
        String domain = publicDomain.endsWith("/") ? publicDomain.substring(0, publicDomain.length() - 1) : publicDomain;
        String objectKey;
        if (url.startsWith(domain + "/")) {
            objectKey = url.substring((domain + "/").length());
        } else {
            return; // URL 格式不符，跳过
        }
        try {
            ossClient.deleteObject(bucketName, objectKey);
        } catch (Exception e) {
            // 删除失败不影响主流程，记录即可
        }
    }
}

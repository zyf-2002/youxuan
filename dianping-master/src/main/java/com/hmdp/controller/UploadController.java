package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * <p>
 * 图片上传。上传接口返回的就是可以直接放进 {@code <img src>} 的公开路径，
 * 例如 {@code /imgs/blogs/7/1/xxx.jpg}，前端原样存进 tb_blog.images 即可，
 * 删除时也把这个路径原样传回来。
 * </p>
 * <p>
 * 磁盘布局 = nginx 静态根 + 公开路径，也就是 {@code <root>/imgs/blogs/...}。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    /**
     * 图片子目录：既是磁盘上的目录名，也是对外暴露的 URL 前缀
     */
    private static final String IMGS = "/imgs";

    /**
     * nginx 静态根，配置项见 application.yaml 的 hmdp.upload.root
     */
    @Value("${hmdp.upload.root}")
    private String webRoot;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 生成新文件名（公开路径）
            String fileName = createNewFileName(image.getOriginalFilename());
            // 保存文件
            image.transferTo(new File(webRoot, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(webRoot, filename);
        // 只允许删 imgs 目录里的普通文件：既挡掉带 ../ 的路径穿越，
        // 也挡住 /js/xxx、/index.html 这类同在前端根下的其它静态文件
        if (!isUnderImgsDir(file) || file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    /**
     * 判断目标路径规范化之后是否仍落在 imgs 目录内
     */
    private boolean isUnderImgsDir(File file) {
        try {
            String base = new File(webRoot, IMGS).getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(base);
        } catch (IOException e) {
            return false;
        }
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(webRoot, StrUtil.format("{}/blogs/{}/{}", IMGS, d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名（公开路径）
        return StrUtil.format("{}/blogs/{}/{}/{}.{}", IMGS, d1, d2, name, suffix);
    }

}

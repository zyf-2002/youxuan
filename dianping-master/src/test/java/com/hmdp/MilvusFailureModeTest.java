package com.hmdp;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>
 * 验证 Milvus 不可达时的失败行为。
 * </p>
 * <p>
 * 背景：Milvus Java SDK 在连接断开时会**无限重试**（实测重试 66 次、持续 200 秒还没停），
 * 会让用户请求一直挂起。所以必须设置 {@code rpcDeadlineMs} 让它有界失败。
 * 这个测试指向一个没有服务的端口，确认调用能在有限时间内抛错。
 * </p>
 */
public class MilvusFailureModeTest {

    private static final String DEAD_URI = "http://127.0.0.1:19599";

    @Test
    public void unreachableMilvusFailsWithinBound() {
        long rpcDeadlineMs = 3000;

        long start = System.currentTimeMillis();
        boolean failed = false;
        String message = "";
        try {
            // 构造器是急切连接的，连不上就直接抛，所以也要放进 try 里
            MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder()
                    .uri(DEAD_URI)
                    .connectTimeoutMs(2000)
                    .rpcDeadlineMs(rpcDeadlineMs)
                    .build());
            float[] vector = new float[1024];
            vector[0] = 1.0f;
            client.search(SearchReq.builder()
                    .collectionName("not_exist")
                    .data(Collections.singletonList(new FloatVec(vector)))
                    .annsField("dense")
                    .topK(1)
                    .build());
        } catch (Exception e) {
            failed = true;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Milvus 不可达：failed=%s 耗时=%.1fs 异常=%s%n",
                failed, elapsed / 1000.0, message);

        assertTrue(failed, "Milvus 不可达时必须抛异常，不能静默返回空结果");
        // 给足重试余量，但不能是"无限"
        assertTrue(elapsed < 60_000,
                "失败应在有限时间内返回，实际耗时 " + elapsed + " ms，疑似无限重试");
    }
}

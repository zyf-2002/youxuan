package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.ShopKnowledge;

/**
 * <p>
 * 店铺知识库服务：把 tb_shop_knowledge 里的正文送进 RAG 知识库
 * </p>
 * <p>
 * 只做「分块 → 向量化 → 父块写 MySQL、叶子块写 Milvus」这一件事，不调用大模型；
 * 触发方式是 {@code ShopKnowledgeServiceImpl} 里的定时扫描（比对 content_hash），
 * 没有对外接口，所以这里只有 MyBatis-Plus 的 CRUD。
 * </p>
 */
public interface IShopKnowledgeService extends IService<ShopKnowledge> {
}

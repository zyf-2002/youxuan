-- ---------------------------------------------------------------------------
-- 把两张优惠券表合并成一张
--
-- 原结构：tb_voucher（券本身）+ tb_seckill_voucher（voucher_id/stock/begin_time/end_time，
--         与主表一对一）。券只有秒杀一种，这张扩展表只是让每次查询多一次 JOIN、
--         每次创建多一次写入，没有任何收益。
-- 新结构：库存与起止时间直接并入 tb_voucher，见 hmdp.sql 里 tb_voucher 的新 DDL。
--
-- 执行顺序（推荐）：
--   1) 执行「第 1 步」——只加列 + 回填，这一步做完旧代码仍然正常
--   2) 重启后端，让新代码生效（新代码只查 tb_voucher，不再 JOIN）
--   3) 确认秒杀流程正常后，执行「第 2 步」删掉旧表
-- 图省事也可以：先停服务，一次跑完，再启服务。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 第 1 步：加列并回填
-- ---------------------------------------------------------------------------
ALTER TABLE `tb_voucher`
  ADD COLUMN `stock` int(8) NOT NULL DEFAULT 0 COMMENT '库存，下单时扣这里' AFTER `actual_value`,
  ADD COLUMN `begin_time` timestamp NULL DEFAULT NULL COMMENT '生效时间' AFTER `stock`,
  ADD COLUMN `end_time` timestamp NULL DEFAULT NULL COMMENT '失效时间' AFTER `begin_time`;

UPDATE `tb_voucher` v
  JOIN `tb_seckill_voucher` sv ON v.id = sv.voucher_id
SET v.stock = sv.stock,
    v.begin_time = sv.begin_time,
    v.end_time = sv.end_time;

-- ---------------------------------------------------------------------------
-- 第 2 步：新代码确认无误后再执行
-- ---------------------------------------------------------------------------
-- DROP TABLE `tb_seckill_voucher`;

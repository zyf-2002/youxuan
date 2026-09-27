-- ---------------------------------------------------------------------------
-- 删掉 tb_voucher_order 里没有用的字段
--
-- pay_type / status / pay_time / use_time / refund_time 是给「支付 → 核销 → 退款」流程用的，
-- 本项目没有那条流程：没有任何代码写它们，读出来永远是默认值（状态永远是「未支付」）。
-- 留着只会让人误以为有支付能力，所以去掉。
--
-- 表里是订单数据，执行前请确认已经不需要这几个字段。当前库里订单表是空的，可以直接跑。
-- ---------------------------------------------------------------------------
ALTER TABLE `tb_voucher_order`
  DROP COLUMN `pay_type`,
  DROP COLUMN `status`,
  DROP COLUMN `pay_time`,
  DROP COLUMN `use_time`,
  DROP COLUMN `refund_time`;

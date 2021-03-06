package cn.lili.modules.store.serviceimpl;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.utils.CurrencyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.common.utils.StringUtils;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.order.order.entity.dos.StoreFlow;
import cn.lili.modules.order.order.entity.enums.FlowTypeEnum;
import cn.lili.modules.order.order.mapper.StoreFlowMapper;
import cn.lili.modules.order.order.service.StoreFlowService;
import cn.lili.modules.store.entity.dos.Bill;
import cn.lili.modules.store.entity.dto.BillSearchParams;
import cn.lili.modules.store.entity.enums.BillStatusEnum;
import cn.lili.modules.store.entity.vos.BillListVO;
import cn.lili.modules.store.entity.vos.StoreDetailVO;
import cn.lili.modules.store.entity.vos.StoreFlowPayDownloadVO;
import cn.lili.modules.store.entity.vos.StoreFlowRefundDownloadVO;
import cn.lili.modules.store.mapper.BillMapper;
import cn.lili.modules.store.service.BillService;
import cn.lili.modules.store.service.StoreDetailService;
import cn.lili.mybatis.util.PageUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.util.Date;
import java.util.List;

/**
 * ????????????????????????
 *
 * @author Chopper
 * @since 2020/11/17 4:28 ??????
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class BillServiceImpl extends ServiceImpl<BillMapper, Bill> implements BillService {

    /**
     * ????????????
     */
    @Autowired
    private StoreDetailService storeDetailService;
    /**
     * ????????????
     */
    @Autowired
    private StoreFlowService storeFlowService;

    @Override
    public void createBill(String storeId, Date startTime, DateTime endTime) {

        //??????????????????
        StoreDetailVO store = storeDetailService.getStoreDetailVO(storeId);
        Bill bill = new Bill();

        //??????????????????
        bill.setStartTime(startTime);
        bill.setEndTime(DateUtil.yesterday());
        bill.setBillStatus(BillStatusEnum.OUT.name());
        bill.setStoreId(storeId);
        bill.setStoreName(store.getStoreName());

        //??????????????????
        bill.setBankAccountName(store.getSettlementBankAccountName());
        bill.setBankAccountNumber(store.getSettlementBankAccountNum());
        bill.setBankCode(store.getSettlementBankJointName());
        bill.setBankName(store.getSettlementBankBranchName());

        //??????????????????
        bill.setSn(SnowFlake.createStr("B"));

        //??????????????????
        Bill orderBill = this.baseMapper.getOrderBill(new QueryWrapper<Bill>()
                .eq("store_id", storeId)
                .eq("flow_type", FlowTypeEnum.PAY.name())
                .between("create_time", startTime, endTime));
        Double orderPrice = 0D;
        if (orderBill != null) {
            bill.setOrderPrice(orderBill.getOrderPrice());
            bill.setCommissionPrice(orderBill.getCommissionPrice());
            bill.setDistributionCommission(orderBill.getDistributionCommission());
            bill.setSiteCouponCommission(orderBill.getSiteCouponCommission());
            bill.setPointSettlementPrice(orderBill.getPointSettlementPrice());
            bill.setKanjiaSettlementPrice(orderBill.getKanjiaSettlementPrice());
            //????????????=????????????+????????????+????????????
            orderPrice = CurrencyUtil.add(CurrencyUtil.add(orderBill.getBillPrice(), orderBill.getPointSettlementPrice()),
                    orderBill.getKanjiaSettlementPrice());
        }


        //??????????????????
        Bill refundBill = this.baseMapper.getRefundBill(new QueryWrapper<Bill>()
                .eq("store_id", storeId)
                .eq("flow_type", FlowTypeEnum.REFUND.name())
                .between("create_time", startTime, endTime));
        Double refundPrice = 0D;
        if (refundBill != null) {
            bill.setRefundPrice(refundBill.getRefundPrice());
            bill.setRefundCommissionPrice(refundBill.getRefundCommissionPrice());
            bill.setDistributionRefundCommission(refundBill.getDistributionRefundCommission());
            bill.setSiteCouponRefundCommission(refundBill.getSiteCouponRefundCommission());
            refundPrice = refundBill.getBillPrice();
        }

        //??????????????????=??????????????????-??????????????????
        Double finalPrice = CurrencyUtil.sub(orderPrice, refundPrice);
        bill.setBillPrice(finalPrice);

        //???????????????
        this.save(bill);

    }

    /**
     * ????????????
     *
     * @param storeId
     * @param endTime ????????????
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void immediatelyBill(String storeId, Long endTime) {

//       Long now = DateUtil.getDateline();
//       //TODO ????????????????????????
//       StoreDetailVO store = new StoreDetailVO();
//       Long startTime = store.getLastBillTime().getTime();
//
//       store.setLastBillTime(new Date(now));
////     TODO   store.save ????????????????????????
//
//       //TODO ????????????????????????????????????
//       BillDTO billDTO = new BillDTO();
//
//       //?????????????????????????????????????????????????????????????????????????????????????????????
//       if (billDTO.getOrderPrice() == 0 && billDTO.getRefundPrice() == 0) {
//           return;
//       }
//
//       this.createBill(storeId, startTime, endTime);
    }

    @Override
    public IPage<BillListVO> billPage(BillSearchParams billSearchParams) {
        QueryWrapper<BillListVO> queryWrapper = billSearchParams.queryWrapper();
        return this.baseMapper.queryBillPage(PageUtil.initPage(billSearchParams), queryWrapper);
    }

    @Override
    public boolean check(String id) {
        Bill bill = this.getById(id);
        //???????????????????????????????????????
        if (!bill.getBillStatus().equals(BillStatusEnum.OUT.name())) {
            throw new ServiceException(ResultCode.BILL_CHECK_ERROR);
        }
        //???????????????????????????
        if (!UserContext.getCurrentUser().getRole().equals(UserEnums.STORE)) {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }
        LambdaUpdateWrapper<Bill> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Bill::getId, id);
        lambdaUpdateWrapper.set(Bill::getBillStatus, BillStatusEnum.CHECK.name());
        return this.update(lambdaUpdateWrapper);
    }

    @Override
    public boolean complete(String id) {
        Bill bill = this.getById(id);
        //??????????????????????????????????????????
        if (!bill.getBillStatus().equals(BillStatusEnum.CHECK.name())) {
            throw new ServiceException(ResultCode.BILL_COMPLETE_ERROR);
        }
        //????????????????????????????????????
        if (!UserContext.getCurrentUser().getRole().equals(UserEnums.MANAGER)) {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }
        LambdaUpdateWrapper<Bill> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Bill::getId, id);
        lambdaUpdateWrapper.set(Bill::getBillStatus, BillStatusEnum.COMPLETE.name());
        return this.update(lambdaUpdateWrapper);
    }

    @Override
    public Integer billNum(BillStatusEnum billStatusEnum) {
        LambdaUpdateWrapper<Bill> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Bill::getBillStatus, billStatusEnum.name());
        lambdaUpdateWrapper.eq(StringUtils.equals(UserContext.getCurrentUser().getRole().name(), UserEnums.STORE.name()),
                Bill::getStoreId, UserContext.getCurrentUser().getStoreId());
        return this.count(lambdaUpdateWrapper);
    }

    @Override
    public void download(HttpServletResponse response, String id) {

        Bill bill = this.getById(id);
        ExcelWriter writer = ExcelUtil.getWriterWithSheet("????????????");
        writer.setSheet("????????????");
        writer.addHeaderAlias("createTime", "????????????");
        writer.setColumnWidth(0, 20);
        writer.addHeaderAlias("orderSn", "????????????");
        writer.setColumnWidth(1, 35);
        writer.addHeaderAlias("storeName", "????????????");
        writer.setColumnWidth(2, 20);
        writer.addHeaderAlias("goodsName", "????????????");
        writer.setColumnWidth(3, 70);
        writer.addHeaderAlias("num", "?????????");
        writer.addHeaderAlias("finalPrice", "????????????");
        writer.addHeaderAlias("commissionPrice", "????????????");
        writer.addHeaderAlias("siteCouponPrice", "???????????????");
        writer.setColumnWidth(7, 12);
        writer.addHeaderAlias("distributionRebate", "????????????");
        writer.addHeaderAlias("pointSettlementPrice", "??????????????????");
        writer.setColumnWidth(9, 12);
        writer.addHeaderAlias("kanjiaSettlementPrice", "??????????????????");
        writer.setColumnWidth(10, 12);
        writer.addHeaderAlias("billPrice", "????????????");
        writer.setColumnWidth(11, 20);

        //??????????????????
        LambdaQueryWrapper<StoreFlow> lambdaQueryWrapper = Wrappers.lambdaQuery();
        lambdaQueryWrapper.eq(StoreFlow::getStoreId, bill.getStoreId());
        lambdaQueryWrapper.between(StoreFlow::getCreateTime, bill.getStartTime(), bill.getCreateTime());
        lambdaQueryWrapper.eq(StoreFlow::getFlowType, FlowTypeEnum.PAY.name());
        List<StoreFlowPayDownloadVO> storeFlowList = storeFlowService.getStoreFlowPayDownloadVO(lambdaQueryWrapper);
        writer.write(storeFlowList, true);

        writer.setSheet("????????????");
        writer.addHeaderAlias("createTime", "????????????");
        writer.setColumnWidth(0, 20);
        writer.addHeaderAlias("orderSn", "????????????");
        writer.setColumnWidth(1, 35);
        writer.addHeaderAlias("refundSn", "????????????");
        writer.setColumnWidth(2, 35);
        writer.addHeaderAlias("storeName", "????????????");
        writer.setColumnWidth(3, 20);
        writer.addHeaderAlias("goodsName", "????????????");
        writer.setColumnWidth(4, 70);
        writer.addHeaderAlias("num", "?????????");
        writer.addHeaderAlias("finalPrice", "????????????");
        writer.addHeaderAlias("commissionPrice", "????????????");
        writer.addHeaderAlias("siteCouponPrice", "???????????????");
        writer.setColumnWidth(8, 12);
        writer.addHeaderAlias("distributionRebate", "????????????");
        writer.addHeaderAlias("pointSettlementPrice", "??????????????????");
        writer.setColumnWidth(10, 12);
        writer.addHeaderAlias("kanjiaSettlementPrice", "??????????????????");
        writer.setColumnWidth(11, 12);
        writer.addHeaderAlias("billPrice", "????????????");
        writer.setColumnWidth(12, 20);

        //??????????????????
        LambdaQueryWrapper<StoreFlow> storeFlowLambdaQueryWrapper = Wrappers.lambdaQuery();
        storeFlowLambdaQueryWrapper.eq(StoreFlow::getStoreId, bill.getStoreId());
        storeFlowLambdaQueryWrapper.between(StoreFlow::getCreateTime, bill.getStartTime(), bill.getCreateTime());
        storeFlowLambdaQueryWrapper.eq(StoreFlow::getFlowType, FlowTypeEnum.PAY.name());
        List<StoreFlowRefundDownloadVO> storeFlowRefundDownloadVOList = storeFlowService.getStoreFlowRefundDownloadVO(storeFlowLambdaQueryWrapper);
        writer.write(storeFlowRefundDownloadVOList, true);

        ServletOutputStream out = null;
        try {
            //?????????????????????????????????
            response.setContentType("application/vnd.ms-excel;charset=utf-8");
            response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(bill.getStoreName() + "-" + bill.getSn(), "UTF8") + ".xls");
            out = response.getOutputStream();
            writer.flush(out, true);
        } catch (Exception e) {
            log.error("?????????????????????", e);
        } finally {
            writer.close();
            IoUtil.close(out);
        }
    }

}

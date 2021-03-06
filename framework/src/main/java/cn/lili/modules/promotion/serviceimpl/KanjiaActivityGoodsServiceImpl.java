package cn.lili.modules.promotion.serviceimpl;


import cn.hutool.core.util.StrUtil;
import cn.lili.common.enums.PromotionTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.utils.DateUtil;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.promotion.entity.dos.KanjiaActivityGoods;
import cn.lili.modules.promotion.entity.dto.KanjiaActivityGoodsDTO;
import cn.lili.modules.promotion.entity.dto.KanjiaActivityGoodsOperationDTO;
import cn.lili.modules.promotion.entity.enums.PromotionStatusEnum;
import cn.lili.modules.promotion.entity.vos.kanjia.KanjiaActivityGoodsListVO;
import cn.lili.modules.promotion.entity.vos.kanjia.KanjiaActivityGoodsParams;
import cn.lili.modules.promotion.entity.vos.kanjia.KanjiaActivityGoodsVO;
import cn.lili.modules.promotion.mapper.KanJiaActivityGoodsMapper;
import cn.lili.modules.promotion.service.KanjiaActivityGoodsService;
import cn.lili.modules.promotion.tools.PromotionTools;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.trigger.enums.DelayTypeEnums;
import cn.lili.trigger.interfaces.TimeTrigger;
import cn.lili.trigger.message.PromotionMessage;
import cn.lili.trigger.model.TimeExecuteConstant;
import cn.lili.trigger.model.TimeTriggerMsg;
import cn.lili.trigger.util.DelayQueueTools;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * ?????????????????????
 *
 * @author qiuqiu
 * @date 2021/7/1
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class KanjiaActivityGoodsServiceImpl extends ServiceImpl<KanJiaActivityGoodsMapper, KanjiaActivityGoods> implements KanjiaActivityGoodsService {

    /**
     * ????????????
     */
    @Autowired
    private GoodsSkuService goodsSkuService;

    /**
     * Rocketmq
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;

    /**
     * ????????????
     */
    @Autowired
    private TimeTrigger timeTrigger;

    /**
     * Mongo
     */
    @Autowired
    private MongoTemplate mongoTemplate;


    @Override
    public Boolean add(KanjiaActivityGoodsOperationDTO kanJiaActivityGoodsOperationDTO) {
        List<KanjiaActivityGoods> kanjiaActivityGoodsList = new ArrayList<>();
        for (KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO : kanJiaActivityGoodsOperationDTO.getPromotionGoodsList()) {
            //??????skuId??????????????????
            GoodsSku goodsSku = this.checkSkuExist(kanJiaActivityGoodsDTO.getSkuId());
            //????????????
            this.checkParam(kanJiaActivityGoodsDTO, goodsSku);
            //????????????????????????????????????????????????
            PromotionTools.checkPromotionTime(kanJiaActivityGoodsOperationDTO.getStartTime().getTime(), kanJiaActivityGoodsOperationDTO.getEndTime().getTime());
            kanJiaActivityGoodsDTO.setStartTime(kanJiaActivityGoodsOperationDTO.getStartTime());
            kanJiaActivityGoodsDTO.setEndTime(kanJiaActivityGoodsOperationDTO.getEndTime());
            //??????????????????????????????????????????????????????
            if (this.checkSkuDuplicate(goodsSku.getId(), kanJiaActivityGoodsDTO) != null) {
                throw new ServiceException("??????id???" + goodsSku.getId() + "???????????????????????????????????????");
            }
            kanJiaActivityGoodsDTO.setGoodsSku(goodsSku);
            kanJiaActivityGoodsDTO.setSkuId(kanJiaActivityGoodsDTO.getSkuId());
            kanJiaActivityGoodsDTO.setThumbnail(goodsSku.getThumbnail());
            kanJiaActivityGoodsDTO.setGoodsName(goodsSku.getGoodsName());
            kanJiaActivityGoodsDTO.setPromotionStatus(PromotionStatusEnum.NEW.name());
            kanJiaActivityGoodsDTO.setOriginalPrice(kanJiaActivityGoodsDTO.getGoodsSku().getPrice());
            kanjiaActivityGoodsList.add(kanJiaActivityGoodsDTO);
        }
        Boolean result = this.saveBatch(kanjiaActivityGoodsList);
        if (result) {
            //??????????????????????????????
            for (KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO : kanJiaActivityGoodsOperationDTO.getPromotionGoodsList()) {
                this.mongoTemplate.save(kanJiaActivityGoodsDTO);
                this.addKanJiaGoodsPromotionTask(kanJiaActivityGoodsDTO);
            }
        }
        return result;
    }


    /**
     * ??????????????????mq??????
     *
     * @param kanJiaActivityGoods ??????????????????
     */
    private void addKanJiaGoodsPromotionTask(KanjiaActivityGoodsDTO kanJiaActivityGoods) {
        PromotionMessage promotionMessage = new PromotionMessage(kanJiaActivityGoods.getId(), PromotionTypeEnum.KANJIA.name(),
                PromotionStatusEnum.START.name(),
                kanJiaActivityGoods.getStartTime(), kanJiaActivityGoods.getEndTime());
        TimeTriggerMsg timeTriggerMsg = new TimeTriggerMsg(TimeExecuteConstant.PROMOTION_EXECUTOR,
                promotionMessage.getStartTime().getTime(),
                promotionMessage,
                DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.PROMOTION, (promotionMessage.getPromotionType() + promotionMessage.getPromotionId())),
                rocketmqCustomProperties.getPromotionTopic());
        //???????????????????????????????????????
        this.timeTrigger.addDelay(timeTriggerMsg);
    }

    @Override
    public IPage<KanjiaActivityGoodsDTO> getForPage(KanjiaActivityGoodsParams kanJiaActivityGoodsParams, PageVO pageVO) {
        IPage<KanjiaActivityGoodsDTO> kanJiaActivityGoodsDTOIPage = new Page<>();
        Query query = kanJiaActivityGoodsParams.mongoQuery();
        if (pageVO != null) {
            PromotionTools.mongoQueryPageParam(query, pageVO);
            kanJiaActivityGoodsDTOIPage.setSize(pageVO.getPageSize());
            kanJiaActivityGoodsDTOIPage.setCurrent(pageVO.getPageNumber());
        }
        List<KanjiaActivityGoodsDTO> kanJiaActivityGoodsDTOS = this.mongoTemplate.find(query, KanjiaActivityGoodsDTO.class);
        kanJiaActivityGoodsDTOIPage.setRecords(kanJiaActivityGoodsDTOS);
        kanJiaActivityGoodsDTOIPage.setTotal(this.mongoTemplate.count(kanJiaActivityGoodsParams.mongoQuery(), KanjiaActivityGoodsDTO.class));
        return kanJiaActivityGoodsDTOIPage;

    }

    @Override
    public IPage<KanjiaActivityGoodsListVO> kanjiaGoodsVOPage(KanjiaActivityGoodsParams kanjiaActivityGoodsParams, PageVO pageVO) {
        return this.baseMapper.kanjiaActivityGoodsVOPage(PageUtil.initPage(pageVO),kanjiaActivityGoodsParams.wrapper());
    }


    /**
     * ????????????Sku?????????
     *
     * @param skuId skuId
     * @return ??????sku
     */
    private GoodsSku checkSkuExist(String skuId) {
        GoodsSku goodsSku = this.goodsSkuService.getGoodsSkuByIdFromCache(skuId);
        if (goodsSku == null) {
            log.error("??????ID???" + skuId + "?????????????????????");
            throw new ServiceException();
        }
        return goodsSku;
    }

    /**
     * ??????????????????????????????
     *
     * @param kanJiaActivityGoodsDTO ??????????????????
     * @param goodsSku               ??????sku??????
     */
    private void checkParam(KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO, GoodsSku goodsSku) {
        //????????????????????????
        if (goodsSku == null) {
            throw new ServiceException(ResultCode.PROMOTION_GOODS_NOT_EXIT);
        }
        //??????????????????
        if (goodsSku.getMarketEnable().equals(GoodsStatusEnum.DOWN.name())) {
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        //?????????????????????????????????sku?????????
        if (goodsSku.getQuantity() < kanJiaActivityGoodsDTO.getStock()) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_STOCK_ERROR);
        }
        //????????????????????????????????????????????????
        if (goodsSku.getPrice() < kanJiaActivityGoodsDTO.getPurchasePrice()) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_PRICE_ERROR);
        }
        //??????????????????????????????????????????
        if (goodsSku.getPrice() < kanJiaActivityGoodsDTO.getSettlementPrice()) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_SETTLEMENT_PRICE_ERROR);
        }
        //????????????????????????
        if (kanJiaActivityGoodsDTO.getHighestPrice() > goodsSku.getPrice() || kanJiaActivityGoodsDTO.getHighestPrice() <= 0) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_HIGHEST_PRICE_ERROR);
        }
        //????????????????????????
        if (kanJiaActivityGoodsDTO.getLowestPrice() > goodsSku.getPrice() || kanJiaActivityGoodsDTO.getLowestPrice() <= 0) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_LOWEST_PRICE_ERROR);
        }
        //??????????????????????????????????????????????????????
        if (kanJiaActivityGoodsDTO.getLowestPrice() > kanJiaActivityGoodsDTO.getHighestPrice()) {
            throw new ServiceException(ResultCode.KANJIA_GOODS_ACTIVE_LOWEST_PRICE_ERROR);
        }
    }

    /**
     * ????????????????????????????????????
     *
     * @param skuId                  ??????SkuId
     * @param kanJiaActivityGoodsDTO ????????????
     * @return ??????????????????
     */
    private KanjiaActivityGoods checkSkuDuplicate(String skuId, KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO) {
        LambdaQueryWrapper<KanjiaActivityGoods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KanjiaActivityGoods::getSkuId, skuId);
        if (kanJiaActivityGoodsDTO != null && StrUtil.isNotEmpty(kanJiaActivityGoodsDTO.getId())) {
            queryWrapper.ne(KanjiaActivityGoods::getId, kanJiaActivityGoodsDTO.getId());
        }
        queryWrapper.ne(KanjiaActivityGoods::getPromotionStatus, PromotionStatusEnum.END.name());

        queryWrapper.ge(KanjiaActivityGoods::getStartTime, kanJiaActivityGoodsDTO.getStartTime());

        queryWrapper.le(KanjiaActivityGoods::getEndTime, kanJiaActivityGoodsDTO.getEndTime());

        return this.getOne(queryWrapper);

    }

    @Override
    public KanjiaActivityGoodsDTO getKanjiaGoodsDetail(String goodsId) {
        KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO = this.mongoTemplate.findById(goodsId, KanjiaActivityGoodsDTO.class);
        if (kanJiaActivityGoodsDTO == null) {
            log.error("id???" + goodsId + "???????????????????????????");
            throw new ServiceException();
        }
        return kanJiaActivityGoodsDTO;
    }

    @Override
    public KanjiaActivityGoodsDTO getKanjiaGoodsBySkuId(String skuId) {

        Query query = new Query();
        query.addCriteria(Criteria.where("promotionStatus").is(PromotionStatusEnum.START.name()));
        query.addCriteria(Criteria.where("skuId").is(skuId));
        List<KanjiaActivityGoodsDTO> kanjiaActivityGoodsDTOS = this.mongoTemplate.find(query, KanjiaActivityGoodsDTO.class);
        return kanjiaActivityGoodsDTOS.get(0);
    }

    @Override
    public KanjiaActivityGoodsVO getKanJiaGoodsVO(String id) {

        KanjiaActivityGoodsVO kanJiaActivityGoodsVO = new KanjiaActivityGoodsVO();
        //??????????????????
        KanjiaActivityGoods kanJiaActivityGoods=this.getById(id);
        //????????????SKU
        GoodsSku goodsSku = this.goodsSkuService.getGoodsSkuByIdFromCache(kanJiaActivityGoods.getSkuId());
        //???????????????????????????????????????
        kanJiaActivityGoodsVO.setGoodsSku(goodsSku);
        kanJiaActivityGoodsVO.setStock(kanJiaActivityGoods.getStock());
        kanJiaActivityGoodsVO.setPurchasePrice(kanJiaActivityGoods.getPurchasePrice());
        //??????????????????
        return kanJiaActivityGoodsVO;

    }

    @Override
    public boolean updateKanjiaActivityGoods(KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO) {
        //??????????????????????????????
        KanjiaActivityGoodsDTO dbKanJiaActivityGoods = this.getKanjiaGoodsDetail(kanJiaActivityGoodsDTO.getId());
        //????????????????????????????????????,?????????????????????????????????????????????
        if (!dbKanJiaActivityGoods.getPromotionStatus().equals(PromotionStatusEnum.NEW.name())) {
            throw new ServiceException(ResultCode.PROMOTION_UPDATE_ERROR);
        }
        //????????????sku??????
        GoodsSku goodsSku = this.checkSkuExist(kanJiaActivityGoodsDTO.getSkuId());
        //??????????????????
        if (goodsSku.getMarketEnable().equals(GoodsStatusEnum.DOWN.name())) {
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        //??????????????????????????????
        this.checkParam(kanJiaActivityGoodsDTO, goodsSku);
        //????????????????????????????????????
        PromotionTools.checkPromotionTime(kanJiaActivityGoodsDTO.getStartTime().getTime(), kanJiaActivityGoodsDTO.getEndTime().getTime());
        //??????????????????????????????????????????????????????
        if (this.checkSkuDuplicate(goodsSku.getId(), kanJiaActivityGoodsDTO) != null) {
            throw new ServiceException("??????id???" + goodsSku.getId() + "???????????????????????????????????????");
        }
        //???????????????
        boolean result = this.updateById(kanJiaActivityGoodsDTO);
        //???????????????????????????????????????????????????
        if (result) {
            this.mongoTemplate.save(kanJiaActivityGoodsDTO);
            if (dbKanJiaActivityGoods.getStartTime().getTime() != kanJiaActivityGoodsDTO.getStartTime().getTime()) {
                PromotionMessage promotionMessage = new PromotionMessage(kanJiaActivityGoodsDTO.getId(), PromotionTypeEnum.KANJIA.name(), PromotionStatusEnum.START.name(), kanJiaActivityGoodsDTO.getStartTime(), kanJiaActivityGoodsDTO.getEndTime());
                //??????????????????
                this.timeTrigger.edit(TimeExecuteConstant.PROMOTION_EXECUTOR,
                        promotionMessage,
                        kanJiaActivityGoodsDTO.getStartTime().getTime(),
                        kanJiaActivityGoodsDTO.getStartTime().getTime(),
                        DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.PROMOTION, (promotionMessage.getPromotionType() + promotionMessage.getPromotionId())),
                        DateUtil.getDelayTime(kanJiaActivityGoodsDTO.getStartTime().getTime()),
                        rocketmqCustomProperties.getPromotionTopic());
            }
        }
        return result;
    }

    @Override
    public boolean deleteKanJiaGoods(List<String> ids) {
        List<String> skuIds = new ArrayList<>();
        for (String id : ids) {
            KanjiaActivityGoodsDTO kanJiaActivityGoodsDTO = this.getKanjiaGoodsDetail(id);
            this.timeTrigger.delete(TimeExecuteConstant.PROMOTION_EXECUTOR,
                    kanJiaActivityGoodsDTO.getStartTime().getTime(),
                    DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.PROMOTION, (PromotionTypeEnum.KANJIA.name() + kanJiaActivityGoodsDTO.getId())),
                    rocketmqCustomProperties.getPromotionTopic());
            skuIds.add(kanJiaActivityGoodsDTO.getSkuId());
        }
        boolean result = this.removeByIds(ids);
        if (result) {
            Query query = new Query();
            query.addCriteria(new Criteria("id").in(ids));
            this.mongoTemplate.remove(query, KanjiaActivityGoodsDTO.class);
        }
        return result;
    }


    @Override
    public KanjiaActivityGoodsDTO getKanJiaGoodsBySku(String skuId) {
        //mongo????????????
        Query query = new Query();
        query.addCriteria(Criteria.where("skuId").is(skuId))
                .addCriteria(Criteria.where("promotionStatus").is(PromotionStatusEnum.START.name()));
        List<KanjiaActivityGoodsDTO> kanjiaActivityGoodsDTOList=this.mongoTemplate.find(query, KanjiaActivityGoodsDTO.class);
        return kanjiaActivityGoodsDTOList.get(0);
    }
}
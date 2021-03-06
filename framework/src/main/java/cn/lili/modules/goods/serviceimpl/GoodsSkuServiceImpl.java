package cn.lili.modules.goods.serviceimpl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.context.UserContext;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.entity.dto.GoodsSearchParams;
import cn.lili.modules.goods.entity.dto.GoodsSkuStockDTO;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.entity.vos.GoodsSkuSpecVO;
import cn.lili.modules.goods.entity.vos.GoodsSkuVO;
import cn.lili.modules.goods.entity.vos.GoodsVO;
import cn.lili.modules.goods.entity.vos.SpecValueVO;
import cn.lili.modules.goods.mapper.GoodsSkuMapper;
import cn.lili.modules.goods.service.CategoryService;
import cn.lili.modules.goods.service.GoodsGalleryService;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.member.entity.dos.FootPrint;
import cn.lili.modules.member.entity.dos.MemberEvaluation;
import cn.lili.modules.member.entity.enums.EvaluationGradeEnum;
import cn.lili.modules.member.service.MemberEvaluationService;
import cn.lili.modules.search.entity.dos.EsGoodsAttribute;
import cn.lili.modules.search.entity.dos.EsGoodsIndex;
import cn.lili.modules.search.service.EsGoodsIndexService;
import cn.lili.modules.search.utils.EsIndexUtil;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.GoodsTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ??????sku???????????????
 *
 * @author pikachu
 * @since 2020-02-23 15:18:56
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class GoodsSkuServiceImpl extends ServiceImpl<GoodsSkuMapper, GoodsSku> implements GoodsSkuService {

    /**
     * ??????
     */
    @Autowired
    private Cache cache;
    /**
     * ??????
     */
    @Autowired
    private CategoryService categoryService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsGalleryService goodsGalleryService;
    /**
     * rocketMq
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    /**
     * rocketMq??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;
    /**
     * ????????????
     */
    @Autowired
    private MemberEvaluationService memberEvaluationService;
    /**
     * ??????
     */
    @Autowired
    private GoodsService goodsService;
    /**
     * ????????????
     */
    @Autowired
    private EsGoodsIndexService goodsIndexService;

    @Override
    public void add(List<Map<String, Object>> skuList, Goods goods) {
        // ??????????????????????????????
        List<GoodsSku> newSkuList;
        //???????????????
        if (skuList != null && !skuList.isEmpty()) {
            // ????????????sku
            newSkuList = this.addGoodsSku(skuList, goods);
        } else {
            throw new ServiceException(ResultCode.MUST_HAVE_GOODS_SKU);
        }

        this.updateStock(newSkuList);
        generateEs(goods);
    }

    @Override
    public void update(List<Map<String, Object>> skuList, Goods goods, Boolean regeneratorSkuFlag) {
        // ??????????????????
        if (skuList == null || skuList.isEmpty()) {
            throw new ServiceException(ResultCode.MUST_HAVE_GOODS_SKU);
        }
        List<GoodsSku> newSkuList;
        //????????????sku??????
        if (Boolean.TRUE.equals(regeneratorSkuFlag)) {
            List<GoodsSkuVO> goodsListByGoodsId = getGoodsListByGoodsId(goods.getId());
            List<String> oldSkuIds = new ArrayList<>();
            //???????????????
            for (GoodsSkuVO goodsSkuVO : goodsListByGoodsId) {
                oldSkuIds.add(goodsSkuVO.getId());
                goodsIndexService.deleteIndexById(goodsSkuVO.getId());
                cache.remove(GoodsSkuService.getCacheKeys(goodsSkuVO.getId()));
            }
            this.removeByIds(oldSkuIds);
            //??????sku??????
            goodsGalleryService.removeByIds(oldSkuIds);
            // ????????????sku
            newSkuList = this.addGoodsSku(skuList, goods);

            //??????mq??????
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.SKU_DELETE.name();
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(oldSkuIds), RocketmqSendCallbackBuilder.commonCallback());
        } else {
            newSkuList = new ArrayList<>();
            for (Map<String, Object> map : skuList) {
                GoodsSku sku = new GoodsSku();
                //??????????????????
                goodsInfo(sku, goods);
                //????????????????????????
                skuInfo(sku, goods, map, null);
                newSkuList.add(sku);
                //?????????????????????????????????es????????????
                if (goods.getIsAuth().equals(GoodsAuthEnum.PASS.name()) && goods.getMarketEnable().equals(GoodsStatusEnum.UPPER.name())) {
                    goodsIndexService.deleteIndexById(sku.getId());
                    this.clearCache(sku.getId());
                }
            }
            this.updateBatchById(newSkuList);
        }
        this.updateStock(newSkuList);
        generateEs(goods);
    }

    /**
     * ????????????sku
     *
     * @param goodsSku sku??????
     */
    @Override
    public void update(GoodsSku goodsSku) {
        this.updateById(goodsSku);
        cache.remove(GoodsSkuService.getCacheKeys(goodsSku.getId()));
        cache.put(GoodsSkuService.getCacheKeys(goodsSku.getId()), goodsSku);
    }


    /**
     * ??????sku??????
     *
     * @param skuId skuID
     */
    @Override
    public void clearCache(String skuId) {
        cache.remove(GoodsSkuService.getCacheKeys(skuId));
    }

    @Override
    public GoodsSku getGoodsSkuByIdFromCache(String id) {
        //??????????????????sku
        GoodsSku goodsSku = (GoodsSku) cache.get(GoodsSkuService.getCacheKeys(id));
        //?????????????????????????????????????????????????????????????????????
        if (goodsSku == null) {
            goodsSku = this.getById(id);
            if (goodsSku == null) {
                return null;
            }
            cache.put(GoodsSkuService.getCacheKeys(id), goodsSku);
        }

        //??????????????????
        Integer integer = (Integer) cache.get(GoodsSkuService.getStockCacheKey(id));

        //???????????????
        if (integer != null) {
            //??????????????????????????????
            if (!goodsSku.getQuantity().equals(integer)) {
                //???????????????????????????
                goodsSku.setQuantity(integer);
                cache.put(GoodsSkuService.getCacheKeys(goodsSku.getId()), goodsSku);
            }
        }
        return goodsSku;
    }

    @Override
    public Map<String, Object> getGoodsSkuDetail(String goodsId, String skuId) {
        Map<String, Object> map = new HashMap<>(16);
        //????????????VO
        GoodsVO goodsVO = goodsService.getGoodsVO(goodsId);
        //??????????????????Sku
        GoodsSku goodsSku = this.getGoodsSkuByIdFromCache(skuId);


        //?????????????????????????????????ID????????????
        if (goodsSku == null) {
            skuId = goodsVO.getSkuList().get(0).getId();
            goodsSku = this.getGoodsSkuByIdFromCache(skuId);
            //??????????????????ID????????????SKU???????????????
            if (goodsSku == null) {
                throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
            }
        }

        //????????????||????????????||?????????????????????||??????????????????????????????????????????
        if (goodsVO == null || goodsVO.getMarketEnable().equals(GoodsStatusEnum.DOWN.name())
                || !goodsVO.getIsAuth().equals(GoodsAuthEnum.PASS.name())
                || Boolean.TRUE.equals(goodsVO.getDeleteFlag())) {
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }

        //?????????????????????????????????
        EsGoodsIndex goodsIndex = goodsIndexService.findById(skuId);
        if (goodsIndex == null) {
            goodsIndex = goodsIndexService.resetEsGoodsIndex(goodsSku, goodsVO.getGoodsParamsDTOList());

            //??????mq??????
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.RESET_GOODS_INDEX.name();
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(Collections.singletonList(goodsIndex)), RocketmqSendCallbackBuilder.commonCallback());
        }

        //????????????
        GoodsSkuVO goodsSkuDetail = this.getGoodsSkuVO(goodsSku);

        //?????????????????????????????????
        if (goodsIndex.getPromotionMap() != null && !goodsIndex.getPromotionMap().isEmpty() && goodsIndex.getPromotionPrice() != null) {
            goodsSkuDetail.setPromotionPrice(goodsIndex.getPromotionPrice());
        }
        map.put("data", goodsSkuDetail);

        //????????????
        String[] split = goodsSkuDetail.getCategoryPath().split(",");
        map.put("categoryName", categoryService.getCategoryNameByIds(Arrays.asList(split)));

        //??????????????????
        map.put("specs", this.groupBySkuAndSpec(goodsVO.getSkuList()));
        map.put("promotionMap", goodsIndex.getPromotionMap());

        //??????????????????
        if (goodsVO.getGoodsParamsDTOList() != null && goodsVO.getGoodsParamsDTOList().size() > 0) {
            map.put("goodsParamsDTOList", goodsVO.getGoodsParamsDTOList());
        }

        //??????????????????
        if (UserContext.getCurrentUser() != null) {
            FootPrint footPrint = new FootPrint(UserContext.getCurrentUser().getId(), goodsId, skuId);
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.VIEW_GOODS.name();
            rocketMQTemplate.asyncSend(destination, footPrint, RocketmqSendCallbackBuilder.commonCallback());
        }
        return map;
    }

    /**
     * ????????????sku??????
     *
     * @param goods ????????????(Id,MarketEnable/IsAuth)
     */
    @Override
    public void updateGoodsSkuStatus(Goods goods) {
        LambdaUpdateWrapper<GoodsSku> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(GoodsSku::getGoodsId, goods.getId());
        updateWrapper.set(GoodsSku::getMarketEnable, goods.getMarketEnable());
        updateWrapper.set(GoodsSku::getIsAuth, goods.getIsAuth());
        updateWrapper.set(GoodsSku::getDeleteFlag, goods.getDeleteFlag());
        boolean update = this.update(updateWrapper);
        if (Boolean.TRUE.equals(update)) {
            List<GoodsSku> goodsSkus = this.getGoodsSkuListByGoodsId(goods.getId());
            for (GoodsSku sku : goodsSkus) {
                cache.remove(GoodsSkuService.getCacheKeys(sku.getId()));
                cache.put(GoodsSkuService.getCacheKeys(sku.getId()), sku);
            }
            generateEs(goods);
        }
    }

    @Override
    public List<GoodsSku> getGoodsSkuByIdFromCache(List<String> ids) {
        List<String> keys = new ArrayList<>();
        for (String id : ids) {
            keys.add(GoodsSkuService.getCacheKeys(id));
        }
        List<GoodsSku> list = cache.multiGet(keys);
        if (list == null || list.isEmpty()) {
            list = new ArrayList<>();
            List<GoodsSku> goodsSkus = listByIds(ids);
            for (GoodsSku skus : goodsSkus) {
                cache.put(GoodsSkuService.getCacheKeys(skus.getId()), skus);
                list.add(skus);
            }
        }
        return list;
    }

    @Override
    public List<GoodsSkuVO> getGoodsListByGoodsId(String goodsId) {
        List<GoodsSku> list = this.list(new LambdaQueryWrapper<GoodsSku>().eq(GoodsSku::getGoodsId, goodsId));
        return this.getGoodsSkuVOList(list);
    }

    /**
     * ??????goodsId????????????goodsSku
     *
     * @param goodsId ??????id
     * @return goodsSku??????
     */
    @Override
    public List<GoodsSku> getGoodsSkuListByGoodsId(String goodsId) {
        return this.list(new LambdaQueryWrapper<GoodsSku>().eq(GoodsSku::getGoodsId, goodsId));
    }

    @Override
    public List<GoodsSkuVO> getGoodsSkuVOList(List<GoodsSku> list) {
        List<GoodsSkuVO> goodsSkuVOS = new ArrayList<>();
        for (GoodsSku goodsSku : list) {
            GoodsSkuVO goodsSkuVO = this.getGoodsSkuVO(goodsSku);
            goodsSkuVOS.add(goodsSkuVO);
        }
        return goodsSkuVOS;
    }

    @Override
    public GoodsSkuVO getGoodsSkuVO(GoodsSku goodsSku) {
        //???????????????
        GoodsSkuVO goodsSkuVO = new GoodsSkuVO(goodsSku);
        //??????sku??????
        JSONObject jsonObject = JSONUtil.parseObj(goodsSku.getSpecs());
        //????????????sku??????
        List<SpecValueVO> specValueVOS = new ArrayList<>();
        //????????????sku??????
        List<String> goodsGalleryList = new ArrayList<>();
        //???????????????sku??????
        for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            SpecValueVO specValueVO = new SpecValueVO();
            if ("images".equals(entry.getKey())) {
                specValueVO.setSpecName(entry.getKey());
                if (entry.getValue().toString().contains("url")) {
                    List<SpecValueVO.SpecImages> specImages = JSONUtil.toList(JSONUtil.parseArray(entry.getValue()), SpecValueVO.SpecImages.class);
                    specValueVO.setSpecImage(specImages);
                    goodsGalleryList = specImages.stream().map(SpecValueVO.SpecImages::getUrl).collect(Collectors.toList());
                }
            } else {
                specValueVO.setSpecName(entry.getKey());
                specValueVO.setSpecValue(entry.getValue().toString());
            }
            specValueVOS.add(specValueVO);
        }
        goodsSkuVO.setGoodsGalleryList(goodsGalleryList);
        goodsSkuVO.setSpecList(specValueVOS);
        return goodsSkuVO;
    }

    @Override
    public IPage<GoodsSku> getGoodsSkuByPage(GoodsSearchParams searchParams) {
        return this.page(PageUtil.initPage(searchParams), searchParams.queryWrapper());
    }

    @Override
    public void updateStocks(List<GoodsSkuStockDTO> goodsSkuStockDTOS) {
        for (GoodsSkuStockDTO goodsSkuStockDTO : goodsSkuStockDTOS) {
            this.updateStock(goodsSkuStockDTO.getSkuId(), goodsSkuStockDTO.getQuantity());
        }
    }

    @Override
    public void updateStock(String skuId, Integer quantity) {
        GoodsSku goodsSku = getGoodsSkuByIdFromCache(skuId);
        if (goodsSku != null) {
            if (quantity <= 0) {
                goodsIndexService.deleteIndexById(goodsSku.getId());
            }
            goodsSku.setQuantity(quantity);
            this.update(new LambdaUpdateWrapper<GoodsSku>().eq(GoodsSku::getId, skuId).set(GoodsSku::getQuantity, quantity));
            cache.put(GoodsSkuService.getCacheKeys(skuId), goodsSku);
            cache.put(GoodsSkuService.getStockCacheKey(skuId), quantity);

            //??????????????????
            List<GoodsSku> goodsSkus = new ArrayList<>();
            goodsSkus.add(goodsSku);
            this.updateGoodsStuck(goodsSkus);
        }
    }

    @Override
    public Integer getStock(String skuId) {
        String cacheKeys = GoodsSkuService.getStockCacheKey(skuId);
        Integer stock = (Integer) cache.get(cacheKeys);
        if (stock != null) {
            return stock;
        } else {
            GoodsSku goodsSku = getGoodsSkuByIdFromCache(skuId);
            cache.put(cacheKeys, goodsSku.getQuantity());
            return goodsSku.getQuantity();
        }
    }

    @Override
    public void updateGoodsStuck(List<GoodsSku> goodsSkus) {
        //??????id?????? hashset ?????????
        Set<String> goodsIds = new HashSet<>();
        for (GoodsSku sku : goodsSkus) {
            goodsIds.add(sku.getGoodsId());
        }
        //???????????????sku??????
        LambdaQueryWrapper<GoodsSku> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(GoodsSku::getGoodsId, goodsIds);
        List<GoodsSku> goodsSkuList = this.list(lambdaQueryWrapper);

        //???????????????????????????
        for (String goodsId : goodsIds) {
            //??????
            Integer quantity = 0;
            for (GoodsSku goodsSku : goodsSkuList) {
                if (goodsId.equals(goodsSku.getGoodsId())) {
                    quantity += goodsSku.getQuantity();
                }
            }
            //????????????????????????
            goodsService.updateStock(goodsId, quantity);
        }


    }

    @Override
    public void updateGoodsSkuCommentNum(String skuId) {
        //??????????????????
        GoodsSku goodsSku = this.getGoodsSkuByIdFromCache(skuId);

        LambdaQueryWrapper<MemberEvaluation> goodEvaluationQueryWrapper = new LambdaQueryWrapper<>();
        goodEvaluationQueryWrapper.eq(MemberEvaluation::getSkuId, goodsSku.getId());
        goodEvaluationQueryWrapper.eq(MemberEvaluation::getGrade, EvaluationGradeEnum.GOOD.name());

        //????????????
        int highPraiseNum = memberEvaluationService.count(goodEvaluationQueryWrapper);

        //????????????????????????
        goodsSku.setCommentNum(goodsSku.getCommentNum() != null ? goodsSku.getCommentNum() + 1 : 1);

        //?????????
        double grade = NumberUtil.mul(NumberUtil.div(highPraiseNum, goodsSku.getCommentNum().doubleValue(), 2), 100);
        goodsSku.setGrade(grade);
        //????????????
        this.update(goodsSku);


        //??????????????????,??????mq??????
        Map<String, Object> updateIndexFieldsMap = EsIndexUtil.getUpdateIndexFieldsMap(
                MapUtil.builder().put("id", goodsSku.getId()).build(),
                MapUtil.builder().put("commentNum", goodsSku.getCommentNum()).put("highPraiseNum", highPraiseNum)
                        .put("grade", grade).build());
        String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.UPDATE_GOODS_INDEX_FIELD.name();
        rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(updateIndexFieldsMap), RocketmqSendCallbackBuilder.commonCallback());

        //???????????????????????????
        goodsService.updateGoodsCommentNum(goodsSku.getGoodsId());
    }

    /**
     * ????????????ES????????????
     *
     * @param goods ????????????
     */
    public void generateEs(Goods goods) {
        String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.GENERATOR_GOODS_INDEX.name();
        //??????mq??????
        rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(goods), RocketmqSendCallbackBuilder.commonCallback());
    }

    /**
     * ????????????
     *
     * @param goodsSkus ??????SKU
     */
    private void updateStock(List<GoodsSku> goodsSkus) {
        //???????????????
        Integer quantity = 0;
        for (GoodsSku sku : goodsSkus) {
            this.updateStock(sku.getId(), sku.getQuantity());
            quantity += sku.getQuantity();
        }
        //??????????????????
        goodsService.updateStock(goodsSkus.get(0).getGoodsId(), quantity);
    }


    /**
     * ??????sku??????
     *
     * @param skuList sku??????
     * @param goods   ????????????
     */
    private List<GoodsSku> addGoodsSku(List<Map<String, Object>> skuList, Goods goods) {
        List<GoodsSku> skus = new ArrayList<>();
        for (Map<String, Object> skuVO : skuList) {
            Map<String, Object> resultMap = this.add(skuVO, goods);
            GoodsSku goodsSku = (GoodsSku) resultMap.get("goodsSku");
            if (goods.getSelfOperated() != null) {
                goodsSku.setSelfOperated(goods.getSelfOperated());
            }
            goodsSku.setGoodsType(goods.getGoodsType());
            skus.add(goodsSku);
            cache.put(GoodsSkuService.getStockCacheKey(goodsSku.getId()), goodsSku.getQuantity());
        }
        this.saveBatch(skus);
        return skus;
    }

    /**
     * ??????????????????
     *
     * @param map   ????????????
     * @param goods ??????
     * @return ????????????
     */
    private Map<String, Object> add(Map<String, Object> map, Goods goods) {
        Map<String, Object> resultMap = new HashMap<>(2);
        GoodsSku sku = new GoodsSku();

        //????????????
        EsGoodsIndex esGoodsIndex = new EsGoodsIndex();

        //??????????????????
        goodsInfo(sku, goods);
        //????????????????????????
        skuInfo(sku, goods, map, esGoodsIndex);

        esGoodsIndex.setGoodsSku(sku);
        resultMap.put("goodsSku", sku);
        resultMap.put("goodsIndex", esGoodsIndex);
        return resultMap;
    }

    /**
     * ?????????????????????????????????
     *
     * @param sku   ??????
     * @param goods ??????
     */
    private void goodsInfo(GoodsSku sku, Goods goods) {
        //??????????????????
        sku.setGoodsId(goods.getId());

        sku.setSellingPoint(goods.getSellingPoint());
        sku.setCategoryPath(goods.getCategoryPath());
        sku.setBrandId(goods.getBrandId());
        sku.setMarketEnable(goods.getMarketEnable());
        sku.setIntro(goods.getIntro());
        sku.setMobileIntro(goods.getMobileIntro());
        sku.setGoodsUnit(goods.getGoodsUnit());
        sku.setGrade(100D);
        //????????????
        sku.setIsAuth(goods.getIsAuth());
        sku.setSalesModel(goods.getSalesModel());
        //????????????
        sku.setStoreId(goods.getStoreId());
        sku.setStoreName(goods.getStoreName());
        sku.setStoreCategoryPath(goods.getStoreCategoryPath());
        sku.setFreightTemplateId(goods.getTemplateId());
        sku.setRecommend(goods.getRecommend());
    }

    /**
     * ????????????????????????
     *
     * @param sku          ????????????
     * @param goods        ??????
     * @param map          ????????????
     * @param esGoodsIndex ????????????
     */
    private void skuInfo(GoodsSku sku, Goods goods, Map<String, Object> map, EsGoodsIndex esGoodsIndex) {

        //??????????????????
        StringBuilder simpleSpecs = new StringBuilder();
        //????????????
        StringBuilder goodsName = new StringBuilder(goods.getGoodsName());
        //?????????????????????
        String thumbnail = "";
        String small = "";
        //?????????
        Map<String, Object> specMap = new HashMap<>(16);
        //????????????
        List<EsGoodsAttribute> attributes = new ArrayList<>();

        //??????????????????
        for (Map.Entry<String, Object> spec : map.entrySet()) {
            //??????????????????
            if (("id").equals(spec.getKey()) || ("sn").equals(spec.getKey()) || ("cost").equals(spec.getKey())
                    || ("price").equals(spec.getKey()) || ("quantity").equals(spec.getKey())
                    || ("weight").equals(spec.getKey())) {
                continue;
            } else {
                specMap.put(spec.getKey(), spec.getValue());
                if (("images").equals(spec.getKey())) {
                    //???????????????????????????
                    List<Map<String, String>> images = (List<Map<String, String>>) spec.getValue();
                    if (images == null || images.isEmpty()) {
                        continue;
                    }
                    //???????????????????????????
                    //???????????????????????????????????????????????????????????????????????????????????????????????????????????????
                    if (spec.getValue() != null && StrUtil.isNotEmpty(spec.getValue().toString())) {
                        thumbnail = goodsGalleryService.getGoodsGallery(images.get(0).get("url")).getThumbnail();
                        small = goodsGalleryService.getGoodsGallery(images.get(0).get("url")).getSmall();
                    }
                } else {
                    if (spec.getValue() != null) {
                        //??????????????????
                        goodsName.append(" ").append(spec.getValue());
                        //??????????????????
                        simpleSpecs.append(" ").append(spec.getValue());
                    }
                }
            }
        }
        //??????????????????
        sku.setGoodsName(goodsName.toString());
        sku.setThumbnail(thumbnail);
        sku.setSmall(small);

        //????????????
        sku.setId(Convert.toStr(map.get("id"), ""));
        sku.setSn(Convert.toStr(map.get("sn")));
        sku.setWeight(Convert.toDouble(map.get("weight"), 0D));
        sku.setPrice(Convert.toDouble(map.get("price"), 0D));
        sku.setCost(Convert.toDouble(map.get("cost"), 0D));
        sku.setQuantity(Convert.toInt(map.get("quantity"), 0));
        sku.setSpecs(JSONUtil.toJsonStr(specMap));
        sku.setSimpleSpecs(simpleSpecs.toString());

        if (esGoodsIndex != null) {
            //????????????
            esGoodsIndex.setAttrList(attributes);
        }
    }

    /**
     * ????????????????????????sku??????????????????
     *
     * @param goodsSkuVOList ??????VO??????
     * @return ??????????????????sku??????????????????
     */
    private List<GoodsSkuSpecVO> groupBySkuAndSpec(List<GoodsSkuVO> goodsSkuVOList) {

        List<GoodsSkuSpecVO> skuSpecVOList = new ArrayList<>();
        for (GoodsSkuVO goodsSkuVO : goodsSkuVOList) {
            GoodsSkuSpecVO specVO = new GoodsSkuSpecVO();
            specVO.setSkuId(goodsSkuVO.getId());
            specVO.setSpecValues(goodsSkuVO.getSpecList());
            specVO.setQuantity(goodsSkuVO.getQuantity());
            skuSpecVOList.add(specVO);
        }
        return skuSpecVOList;
    }

}

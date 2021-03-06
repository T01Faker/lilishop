package cn.lili.modules.member.serviceimpl;


import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.CharSequenceUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.context.ThreadContextHolder;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.SwitchEnum;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.Token;
import cn.lili.common.utils.BeanUtil;
import cn.lili.common.utils.CookieUtil;
import cn.lili.common.utils.StringUtils;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.connect.config.ConnectAuthEnum;
import cn.lili.modules.connect.entity.Connect;
import cn.lili.modules.connect.entity.dto.ConnectAuthUser;
import cn.lili.modules.connect.entity.enums.ConnectEnum;
import cn.lili.modules.connect.service.ConnectService;
import cn.lili.common.utils.UuidUtils;
import cn.lili.modules.member.aop.annotation.PointLogPoint;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.entity.dto.*;
import cn.lili.modules.member.entity.enums.PointTypeEnum;
import cn.lili.modules.member.entity.vo.MemberDistributionVO;
import cn.lili.modules.member.entity.vo.MemberSearchVO;
import cn.lili.modules.member.entity.vo.MemberVO;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.token.MemberTokenGenerate;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.enums.StoreStatusEnum;
import cn.lili.modules.store.service.StoreService;
import cn.lili.common.sensitive.SensitiveWordsFilter;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.MemberTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * ???????????????????????????
 *
 * @author Chopper
 * @since 2021-03-29 14:10:16
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class MemberServiceImpl extends ServiceImpl<MemberMapper, Member> implements MemberService {

    /**
     * ??????token
     */
    @Autowired
    private MemberTokenGenerate memberTokenGenerate;
    /**
     * ??????token
     */
    @Autowired
    private StoreTokenGenerate storeTokenGenerate;
    /**
     * ????????????
     */
    @Autowired
    private ConnectService connectService;
    /**
     * ??????
     */
    @Autowired
    private StoreService storeService;
    /**
     * RocketMQ ??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;
    /**
     * RocketMQ
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    /**
     * ??????
     */
    @Autowired
    private Cache cache;

    @Override
    public Member findByUsername(String userName) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper();
        queryWrapper.eq("username", userName);
        return this.baseMapper.selectOne(queryWrapper);
    }


    @Override
    public Member getUserInfo() {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser != null) {
            return this.findByUsername(tokenUser.getUsername());
        }
        throw new ServiceException(ResultCode.USER_NOT_LOGIN);
    }

    @Override
    public boolean findByMobile(String uuid, String mobile) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper();
        queryWrapper.eq("mobile", mobile);
        Member member = this.baseMapper.selectOne(queryWrapper);
        if (member == null) {
            throw new ServiceException(ResultCode.USER_NOT_PHONE);
        }
        cache.put(CachePrefix.FIND_MOBILE + uuid, mobile, 300L);

        return true;
    }

    @Override
    public Token usernameLogin(String username, String password) {
        Member member = this.findMember(username);
        //????????????????????????
        if (member == null || !member.getDisabled()) {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }
        //??????????????????????????????
        if (!new BCryptPasswordEncoder().matches(password, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_PASSWORD_ERROR);
        }
        loginBindUser(member);
        return memberTokenGenerate.createToken(member.getUsername(), false);
    }

    @Override
    public Token usernameStoreLogin(String username, String password) {

        Member member = this.findMember(username);
        //????????????????????????
        if (member == null || !member.getDisabled()) {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }
        //??????????????????????????????
        if (!new BCryptPasswordEncoder().matches(password, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_PASSWORD_ERROR);
        }
        //??????????????????????????????
        if (member.getHaveStore()) {
            Store store = storeService.getById(member.getStoreId());
            if (!store.getStoreDisable().equals(StoreStatusEnum.OPEN.name())) {
                throw new ServiceException(ResultCode.STORE_CLOSE_ERROR);
            }
        } else {
            throw new ServiceException(ResultCode.USER_NOT_EXIST);
        }

        return storeTokenGenerate.createToken(member.getUsername(), false);
    }

    /**
     * ??????????????????????????????
     *
     * @param userName
     * @return
     */
    private Member findMember(String userName) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper();
        queryWrapper.eq("username", userName).or().eq("mobile", userName);
        return this.getOne(queryWrapper);
    }

    @Override
    public Token autoRegister(ConnectAuthUser authUser) {

        if (StringUtils.isEmpty(authUser.getNickname())) {
            authUser.setNickname("????????????");
        }
        if (StringUtils.isEmpty(authUser.getAvatar())) {
            authUser.setAvatar("https://i.loli.net/2020/11/19/LyN6JF7zZRskdIe.png");
        }
        try {
            String username = UuidUtils.getUUID();
            Member member = new Member(username, UuidUtils.getUUID(), authUser.getAvatar(), authUser.getNickname(),
                    authUser.getGender() != null ? Convert.toInt(authUser.getGender().getCode()) : 0);
            //????????????
            this.save(member);
            Member loadMember = this.findByUsername(username);
            //??????????????????
            loginBindUser(loadMember, authUser.getUuid(), authUser.getSource());
            return memberTokenGenerate.createToken(username, false);
        } catch (ServiceException e) {
            log.error("?????????????????????????????????", e);
            throw e;
        } catch (Exception e) {
            log.error("?????????????????????", e);
            throw new ServiceException(ResultCode.USER_AUTO_REGISTER_ERROR);
        }
    }

    @Override
    public Token autoRegister() {
        ConnectAuthUser connectAuthUser = this.checkConnectUser();
        return this.autoRegister(connectAuthUser);
    }

    @Override
    public Token refreshToken(String refreshToken) {
        return memberTokenGenerate.refreshToken(refreshToken);
    }

    @Override
    public Token refreshStoreToken(String refreshToken) {
        return storeTokenGenerate.refreshToken(refreshToken);
    }

    @Override
    public Token mobilePhoneLogin(String mobilePhone) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("mobile", mobilePhone);
        Member member = this.baseMapper.selectOne(queryWrapper);
        //?????????????????????????????????????????????
        if (member == null) {
            member = new Member(mobilePhone, UuidUtils.getUUID(), mobilePhone);
            //????????????
            this.save(member);
            String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_REGISTER.name();
            rocketMQTemplate.asyncSend(destination, member, RocketmqSendCallbackBuilder.commonCallback());
        }
        loginBindUser(member);
        return memberTokenGenerate.createToken(member.getUsername(), false);
    }

    @Override
    public Member editOwn(MemberEditDTO memberEditDTO) {
        //??????????????????
        Member member = this.findByUsername(Objects.requireNonNull(UserContext.getCurrentUser()).getUsername());
        //????????????????????????
        BeanUtil.copyProperties(memberEditDTO, member);
        //????????????
        this.updateById(member);
        return member;
    }

    @Override
    public Member modifyPass(String oldPassword, String newPassword) {
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        Member member = this.getById(tokenUser.getId());
        //?????????????????????????????????
        if (!new BCryptPasswordEncoder().matches(oldPassword, member.getPassword())) {
            throw new ServiceException(ResultCode.USER_OLD_PASSWORD_ERROR);
        }
        //??????????????????
        LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Member::getId, member.getId());
        lambdaUpdateWrapper.set(Member::getPassword, new BCryptPasswordEncoder().encode(newPassword));
        this.update(lambdaUpdateWrapper);
        return member;
    }

    @Override
    public Token register(String userName, String password, String mobilePhone) {
        //??????????????????
        checkMember(userName, mobilePhone);
        //??????????????????
        Member member = new Member(userName, new BCryptPasswordEncoder().encode(password), mobilePhone);
        //?????????????????????????????????
        if (this.save(member)) {
            Token token = memberTokenGenerate.createToken(member.getUsername(), false);
            String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_REGISTER.name();
            rocketMQTemplate.asyncSend(destination, member, RocketmqSendCallbackBuilder.commonCallback());
            return token;
        }
        return null;
    }

    @Override
    public boolean changeMobile(String mobile) {
        AuthUser tokenUser = Objects.requireNonNull(UserContext.getCurrentUser());
        Member member = this.findByUsername(tokenUser.getUsername());

        //????????????????????????????????????ID?????????????????????ID
        if (!Objects.equals(tokenUser.getId(), member.getId())) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        //?????????????????????
        LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.eq(Member::getId, member.getId());
        lambdaUpdateWrapper.set(Member::getMobile, mobile);
        return this.update(lambdaUpdateWrapper);
    }

    @Override
    public boolean resetByMobile(String uuid, String password) {
        String phone = cache.get(CachePrefix.FIND_MOBILE + uuid).toString();
        //??????????????????????????????????????????????????????
        if (phone != null) {
            //????????????
            LambdaUpdateWrapper<Member> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
            lambdaUpdateWrapper.eq(Member::getMobile, phone);
            lambdaUpdateWrapper.set(Member::getPassword, new BCryptPasswordEncoder().encode(password));
            return this.update(lambdaUpdateWrapper);
        } else {
            throw new ServiceException(ResultCode.USER_PHONE_NOT_EXIST);
        }

    }

    @Override
    public Member addMember(MemberAddDTO memberAddDTO) {

        //??????????????????
        checkMember(memberAddDTO.getUsername(), memberAddDTO.getMobile());

        //????????????
        Member member = new Member(memberAddDTO.getUsername(), new BCryptPasswordEncoder().encode(memberAddDTO.getPassword()), memberAddDTO.getMobile());
        this.save(member);
        String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_REGISTER.name();
        rocketMQTemplate.asyncSend(destination, member, RocketmqSendCallbackBuilder.commonCallback());
        return member;
    }

    @Override
    public Member updateMember(ManagerMemberEditDTO managerMemberEditDTO) {
        //????????????????????????????????????ID?????????????????????ID
        AuthUser tokenUser = UserContext.getCurrentUser();
        if (tokenUser == null) {
            throw new ServiceException(ResultCode.USER_NOT_LOGIN);
        }
        //???????????????????????????
        if (com.baomidou.mybatisplus.core.toolkit.StringUtils.isNotBlank(managerMemberEditDTO.getNickName())) {
            managerMemberEditDTO.setNickName(SensitiveWordsFilter.filter(managerMemberEditDTO.getNickName()));
        }
        //????????????????????????????????????
        if (com.baomidou.mybatisplus.core.toolkit.StringUtils.isNotBlank(managerMemberEditDTO.getPassword())) {
            managerMemberEditDTO.setPassword(new BCryptPasswordEncoder().encode(managerMemberEditDTO.getPassword()));
        }
        //??????????????????
        Member member = this.findByUsername(managerMemberEditDTO.getUsername());
        //????????????????????????
        BeanUtil.copyProperties(managerMemberEditDTO, member);
        this.updateById(member);
        return member;
    }

    @Override
    public IPage<MemberVO> getMemberPage(MemberSearchVO memberSearchVO, PageVO page) {
        QueryWrapper<Member> queryWrapper = Wrappers.query();
        //???????????????
        queryWrapper.like(StringUtils.isNotBlank(memberSearchVO.getUsername()), "username", memberSearchVO.getUsername());
        //???????????????
        queryWrapper.like(StringUtils.isNotBlank(memberSearchVO.getNickName()), "nick_name", memberSearchVO.getNickName());
        //????????????????????????
        queryWrapper.like(StringUtils.isNotBlank(memberSearchVO.getMobile()), "mobile", memberSearchVO.getMobile());
        //????????????????????????
        queryWrapper.eq(StringUtils.isNotBlank(memberSearchVO.getDisabled()), "disabled",
                memberSearchVO.getDisabled().equals(SwitchEnum.OPEN.name()) ? 1 : 0);
        queryWrapper.orderByDesc("create_time");
        return this.baseMapper.pageByMemberVO(PageUtil.initPage(page), queryWrapper);
    }

    @Override
    @PointLogPoint
    public Boolean updateMemberPoint(Long point, String type, String memberId, String content) {
        //????????????????????????
        Member member = this.getById(memberId);
        if (member != null) {
            //??????????????????????????????
            long currentPoint;
            //?????????????????????
            long totalPoint = member.getTotalPoint();
            //??????????????????
            if (type.equals(PointTypeEnum.INCREASE.name())) {
                currentPoint = member.getPoint() + point;
                //????????????????????? ???????????????????????????
                totalPoint = totalPoint + point;
            }
            //??????????????????
            else {
                currentPoint = member.getPoint() - point < 0 ? 0 : member.getPoint() - point;
            }
            member.setPoint(currentPoint);
            member.setTotalPoint(totalPoint);
            Boolean result = this.updateById(member);
            if (result) {
                //??????????????????
                MemberPointMessage memberPointMessage = new MemberPointMessage();
                memberPointMessage.setPoint(point);
                memberPointMessage.setType(type);
                memberPointMessage.setMemberId(memberId);
                String destination = rocketmqCustomProperties.getMemberTopic() + ":" + MemberTagsEnum.MEMBER_POINT_CHANGE.name();
                rocketMQTemplate.asyncSend(destination, memberPointMessage, RocketmqSendCallbackBuilder.commonCallback());
                return true;
            }
            return false;

        }
        throw new ServiceException(ResultCode.USER_NOT_EXIST);
    }

    @Override
    public Boolean updateMemberStatus(List<String> memberIds, Boolean status) {
        UpdateWrapper<Member> updateWrapper = Wrappers.update();
        updateWrapper.set("disabled", status);
        updateWrapper.in("id", memberIds);

        return this.update(updateWrapper);
    }

    @Override
    public List<MemberDistributionVO> distribution() {
        List<MemberDistributionVO> memberDistributionVOS = this.baseMapper.distribution();
        return memberDistributionVOS;
    }

    /**
     * ???????????????????????????
     *
     * @param mobilePhone ?????????
     * @return ??????
     */
    private Member findByPhone(String mobilePhone) {
        QueryWrapper<Member> queryWrapper = new QueryWrapper();
        queryWrapper.eq("mobile", mobilePhone);
        return this.baseMapper.selectOne(queryWrapper);
    }

    /**
     * ??????cookie????????????????????????
     *
     * @param uuid uuid
     * @param type ??????
     * @return
     */
    private ConnectAuthUser getConnectAuthUser(String uuid, String type) {
        Object context = cache.get(ConnectService.cacheKey(type, uuid));
        if (context != null) {
            return (ConnectAuthUser) context;
        }
        return null;
    }

    /**
     * ????????????????????????cookie?????????????????????????????????
     *
     * @param member  ??????
     * @param unionId unionId
     * @param type    ??????
     */
    private void loginBindUser(Member member, String unionId, String type) {
        Connect connect = connectService.queryConnect(
                ConnectQueryDTO.builder().unionId(unionId).unionType(type).build()
        );
        if (connect == null) {
            connect = new Connect(member.getId(), unionId, type);
            connectService.save(connect);
        }
    }

    /**
     * ????????????????????????cookie?????????????????????????????????
     *
     * @param member ??????
     */
    private void loginBindUser(Member member) {
        //??????cookie???????????????
        String uuid = CookieUtil.getCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpRequest());
        String connectType = CookieUtil.getCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpRequest());
        //?????????????????????????????????
        if (StringUtils.isNotEmpty(uuid) && StringUtils.isNotEmpty(connectType)) {
            try {
                //????????????
                ConnectAuthUser connectAuthUser = getConnectAuthUser(uuid, connectType);
                if (connectAuthUser == null) {
                    return;
                }
                Connect connect = connectService.queryConnect(
                        ConnectQueryDTO.builder().unionId(connectAuthUser.getUuid()).unionType(connectType).build()
                );
                if (connect == null) {
                    connect = new Connect(member.getId(), connectAuthUser.getUuid(), connectType);
                    connectService.save(connect);
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                log.error("????????????????????????????????????", e);
            } finally {
                //???????????????????????????????????????cookie????????????
                CookieUtil.delCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpResponse());
                CookieUtil.delCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpResponse());
            }
        }

    }


    /**
     * ?????????????????????????????????????????????
     * ??????null??????
     * ????????????1???redis?????????????????????????????????  2????????????????????????
     *
     * @return ???????????????????????????????????????????????????????????????null?????????????????????????????????
     */
    private ConnectAuthUser checkConnectUser() {
        //??????cookie???????????????
        String uuid = CookieUtil.getCookie(ConnectService.CONNECT_COOKIE, ThreadContextHolder.getHttpRequest());
        String connectType = CookieUtil.getCookie(ConnectService.CONNECT_TYPE, ThreadContextHolder.getHttpRequest());

        //?????????????????????????????????
        if (StringUtils.isNotEmpty(uuid) && StringUtils.isNotEmpty(connectType)) {
            try {
                //?????? ????????????????????????
                ConnectAuthEnum authInterface = ConnectAuthEnum.valueOf(connectType);

                ConnectAuthUser connectAuthUser = getConnectAuthUser(uuid, connectType);
                if (connectAuthUser == null) {
                    throw new ServiceException(ResultCode.USER_OVERDUE_CONNECT_ERROR);
                }
                //?????????????????????????????????
                LambdaQueryWrapper<Connect> queryWrapper = new LambdaQueryWrapper<>();
                Connect connect = connectService.queryConnect(
                        ConnectQueryDTO.builder().unionType(connectType).unionId(connectAuthUser.getUuid()).build()
                );
                //?????????????????????true???????????????????????????
                if (connect == null) {
                    connectAuthUser.setConnectEnum(authInterface);
                    return connectAuthUser;
                } else {
                    throw new ServiceException(ResultCode.USER_CONNECT_BANDING_ERROR);
                }
            } catch (Exception e) {
                throw e;
            }
        } else {
            throw new ServiceException(ResultCode.USER_CONNECT_NOT_EXIST_ERROR);
        }
    }

    @Override
    public Integer getMemberNum(MemberSearchVO memberSearchVO) {
        QueryWrapper<Member> queryWrapper = Wrappers.query();
        //???????????????
        queryWrapper.like(StringUtils.isNotBlank(memberSearchVO.getUsername()), "username", memberSearchVO.getUsername());
        //????????????????????????
        queryWrapper.like(StringUtils.isNotBlank(memberSearchVO.getMobile()), "mobile", memberSearchVO.getMobile());
        //??????????????????
        queryWrapper.eq(StringUtils.isNotBlank(memberSearchVO.getDisabled()), "disabled",
                memberSearchVO.getDisabled().equals(SwitchEnum.OPEN.name()) ? 1 : 0);
        queryWrapper.orderByDesc("create_time");
        return this.count(queryWrapper);
    }

    /**
     * ??????
     */
    @Override
    public void logout(UserEnums userEnums) {
        String currentUserToken = UserContext.getCurrentUserToken();
        if (CharSequenceUtil.isNotEmpty(currentUserToken)) {
            cache.remove(CachePrefix.ACCESS_TOKEN.getPrefix(userEnums) + currentUserToken);
        }
    }

    /**
     * ????????????
     *
     * @param userName    ????????????
     * @param mobilePhone ?????????
     */
    private void checkMember(String userName, String mobilePhone) {
        //???????????????????????????
        if (findByUsername(userName) != null) {
            throw new ServiceException(ResultCode.USER_NAME_EXIST);
        }
        //???????????????????????????
        if (findByPhone(mobilePhone) != null) {
            throw new ServiceException(ResultCode.USER_PHONE_EXIST);
        }
    }
}
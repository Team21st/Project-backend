package com.CS353.cs353project.service;

import com.CS353.cs353project.bean.*;
import com.CS353.cs353project.dao.mapper.Trade.*;
import com.CS353.cs353project.dao.mapper.UserMapper;
import com.CS353.cs353project.param.evt.Message.OperateCancelOperationMsgEvt;
import com.CS353.cs353project.param.evt.Message.SendCancelOrderMsgEvt;
import com.CS353.cs353project.param.evt.Message.SendOffShelveReasonEvt;
import com.CS353.cs353project.param.evt.Trade.*;
import com.CS353.cs353project.param.evt.Trade.Order.*;
import com.CS353.cs353project.param.evt.Trade.ShoppingCart.AddShoppingCartEvt;
import com.CS353.cs353project.param.evt.Trade.ShoppingCart.EditShoppingCartEvt;
import com.CS353.cs353project.param.evt.Trade.ShoppingCart.PlaceCartOrderEvt;
import com.CS353.cs353project.param.evt.User.UserRegisterEvt;
import com.CS353.cs353project.param.model.Oss.AliyunOssResultModel;
import com.CS353.cs353project.param.model.Trade.QueryCommoditiesModel;
import com.CS353.cs353project.param.model.Trade.QueryOrderModel;
import com.CS353.cs353project.param.model.Trade.QueryShoppingCartModel;
import com.CS353.cs353project.param.out.ServiceHeader;
import com.CS353.cs353project.param.out.ServiceResp;
import com.CS353.cs353project.utils.AliyunOSSUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;

@Service
public class TradeService {
    Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private CommodityMapper commodityMapper;
    @Autowired
    private MessageService messageService;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CommPicMapper commPicMapper;

    /**
     * 上架图书接口(提交审核)
     */
    public ServiceResp bookOnShelve(HttpServletRequest request, MultipartFile[] files, BookOnShelveEvt evt) {
        //用户邮箱(唯一账户识别标准,而不是用户名)
        String userEmail = (String) request.getAttribute("userEmail");
        String userName = (String) request.getAttribute("userName");
        String sellerNo = (String) request.getAttribute("userNo");

        CommodityBean commodityBean = new CommodityBean();
        BeanUtils.copyProperties(evt, commodityBean);
        String bookNo = StringUtils.replace(UUID.randomUUID().toString(), "-", "");
        commodityBean.setBookNo(bookNo);
        commodityBean.setSellerName(userName);
        commodityBean.setRecommend(0);
        commodityBean.setBookStock(evt.getBookStock());
        commodityBean.setBookSale(0);
        commodityBean.setSellerNo(sellerNo);
        if (evt.getCustomTags() != null) {
            commodityBean.setCustomTags(evt.getCustomTags());
        }
        commodityBean.setStatus("E");
        commodityBean.setCreateUser(userEmail);
        commodityBean.setUpdateUser(userEmail);
        commodityBean.setAuditStatus("0");

        //上传图书实物图，不允许用户不传图片
        int count = 1;
        List<String> insertPicResult = new ArrayList<>();
        if (files == null) {
            return new ServiceResp().error("you must upload book picture");
        }
        for (MultipartFile file : files) {
            String ossUrl = "SHBM/OnShelveBook/" + userEmail + "/" + evt.getBookName() + "_" + count;
            AliyunOssResultModel resultModel = AliyunOSSUtil.uploadFile(file, ossUrl);
            if (!resultModel.isSuccess()) {//若上传图片不成功
                return new ServiceResp().error(resultModel.getMsg());
            }
            String bookPicUrl = resultModel.getUrl();
            CommPicBean commPicBean = new CommPicBean();
            commPicBean.setPictureUrl(bookPicUrl);
            commPicBean.setCreateUser(userEmail);
            commPicBean.setUpdateUser(userEmail);
            commPicBean.setStatus("E");
            commPicBean.setBookNo(bookNo);
            commPicBean.setCommPicNo(StringUtils.replace(UUID.randomUUID().toString(), "-", ""));
            int result3 = commPicMapper.insert(commPicBean);
            if (result3 != 1) {
                insertPicResult.add("failed when insert number " + count + " picture");
            }
            count++;
        }

        //存入数据库(更新商品记录)
        int result = commodityMapper.insert(commodityBean);
        if (result == 1) {
            logger.info(userEmail + " make books on shelve");
            return new ServiceResp().success(" make books on shelve success");
        }
        UserBean sellerInfo = userMapper.selectById(sellerNo);
        if (sellerInfo == null) {
            return new ServiceResp().error("seller record not found");
        }
        sellerInfo.setReleaseCommNum(sellerInfo.getReleaseCommNum() + 1);
        sellerInfo.setUpdateUser(userEmail);
        int result2 = userMapper.updateById(sellerInfo);
        if (result2 != 1) {
            return new ServiceResp().error("update seller ReleaseCommNum error");
        }
        //若有某几张图片上传失败
        if (insertPicResult.size() != 0) {
            ServiceHeader respHead = new ServiceHeader();
            respHead.setRespCode(-1);
            respHead.setRespMsg("There are some picture of book insert failed, you can try to upload them again in edit page");
            ServiceResp resp = new ServiceResp();
            resp.setHead(respHead);
            resp.setBody(insertPicResult);
            return resp;
        }
        return new ServiceResp().error("On shelve successful");
    }

    /**
     * 修改商品信息
     */
    public ServiceResp editBookOnShelve(HttpServletRequest request, EditBookOnShelveEvt evt) {
        CommodityBean recordInfo = commodityMapper.queryOneRecord(evt.getBookNo());
        if (recordInfo == null) {
            return new ServiceResp().error("can't find the record information");
        }
        BeanUtils.copyProperties(evt, recordInfo);
        recordInfo.setAuditStatus("0");//设置待审核
        recordInfo.setUpdateUser((String) request.getAttribute("userEmail"));
        int result = commodityMapper.updateById(recordInfo);
        if (result != 1) {
            return new ServiceResp().error("edit on shelve record failed");
        }
        return new ServiceResp().success("edit on shelve record  success");
    }

    /**
     * 下架商品
     */
    public ServiceResp bookOffShelve(HttpServletRequest request, BookOffShelveEvt evt) {
        CommodityBean recordInfo = commodityMapper.queryOneRecord(evt.getBookNo());
        String userEmail = (String) request.getAttribute("userEmail");
        if (recordInfo == null) {
            return new ServiceResp().error("can't find the record information");
        }

        String auditStatus = recordInfo.getAuditStatus();
        recordInfo.setStatus("D");
        recordInfo.setUpdateUser(userEmail);
        if (evt.getUserRole() == 1) {//若是由管理员下架的，发邮件说明原因
            SendOffShelveReasonEvt msgEvt = new SendOffShelveReasonEvt();
            msgEvt.setBookName(recordInfo.getBookName());
            msgEvt.setReason(evt.getReason());
            msgEvt.setEmail(recordInfo.getCreateUser());
            messageService.sendOffShelveReason(msgEvt);
        }
        //修改发布商品信息
        if ("1".equals(auditStatus)) {//当时已发布的商品下架时才需要减少
            String sellerNo = recordInfo.getSellerNo();
            UserBean sellerInfo = userMapper.selectById(sellerNo);
            if (sellerInfo == null) {
                return new ServiceResp().error("can't find the seller record information");
            }
            sellerInfo.setUpdateUser(userEmail);
            //商家已上架商品数量-1
            Integer releaseCommNum = sellerInfo.getReleaseCommNum() - 1;
            sellerInfo.setReleaseCommNum(releaseCommNum);
            int result = userMapper.updateById(sellerInfo);
            if (result != 1) {
                return new ServiceResp().error("reduce releaseCommNum failed");
            }
        }
        int result = commodityMapper.updateById(recordInfo);
        if (result != 1) {
            return new ServiceResp().error("delete on shelve record failed");
        }
        return new ServiceResp().success("delete on shelve record success");
    }

    /**
     * 查询商品
     */
    public ServiceResp queryCommodities(QueryCommoditiesEvt evt) {
        Page<QueryCommoditiesModel> page = new Page<>(evt.getQueryPage(), evt.getQuerySize());
        Page<QueryCommoditiesModel> modelPage = commodityMapper.queryCommodities(evt, page);
        if (modelPage.getRecords() == null) {
            return new ServiceResp().error("can not find relative books");
        }
        //拼接成价格格式，并且存入商品图片
        DecimalFormat df = new DecimalFormat();
        df.applyPattern("#,##0.00");
        for (QueryCommoditiesModel model : page.getRecords()) {
            //查询对应商家信息，并插入对应头像
            UserBean userBean=userMapper.selectById(model.getSellerNo());
            if(userBean==null){
                return  new ServiceResp().error("can not find relative seller, sellerNo: "+model.getSellerNo());
            }
            model.setSellerPic(userBean.getProfileUrl());
            //金额格式化
            BigDecimal decimalPrice = new BigDecimal(model.getBookPrice());
            StringBuffer sb1 = new StringBuffer(df.format(decimalPrice));
            sb1.insert(0, "$");
            model.setTruePrice(new String(sb1));
            //存入商品图片
            QueryWrapper<CommPicBean> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("bookNo", model.getBookNo());
            queryWrapper.eq("status", "E");
            List<CommPicBean> picUrlList = commPicMapper.selectList(queryWrapper);
            if (picUrlList != null) {
                List<String> picUrlBackList = new ArrayList<>();
                for (int i = 1; i < picUrlList.size() + 1; i++) {
                    String bookUrlName = "bookPicUrl";
                    bookUrlName += i;
                    if (bookUrlName.equals("bookPicUrl1")) {
                        picUrlBackList.add(picUrlList.get(i - 1).getPictureUrl());
                    } else if (bookUrlName.equals("bookPicUrl2")) {
                        picUrlBackList.add(picUrlList.get(i - 1).getPictureUrl());
                    } else if (bookUrlName.equals("bookPicUrl3")) {
                        picUrlBackList.add(picUrlList.get(i - 1).getPictureUrl());
                    } else if (bookUrlName.equals("bookPicUrl4")) {
                        picUrlBackList.add(picUrlList.get(i - 1).getPictureUrl());
                    }
                    model.setPicUrlBackList(picUrlBackList);
                }
            }
        }
        return new ServiceResp().success(modelPage);
    }

    /**
     * 展示商家
     */
    public ServiceResp queryBusiness(QueryBusinessEvt evt) {
        List<Integer> typeList = new ArrayList<>();
        if (evt.getSortType() != null) {
            typeList = Arrays.asList(evt.getSortType());
        }
        QueryWrapper<UserBean> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("userNo,userName,userEmail,userInfo,releaseCommNum,soldCommNum,createTime,profileUrl")
                .eq("status", "E")
                .gt("releaseCommNum", 0)
                .eq("authentication", 2);
        //8.00
        //string $8.00
        if (StringUtils.isNotBlank(evt.getSellerName())) {
            queryWrapper.like("userName", evt.getSellerName());
        }
        if (evt.getSortType() != null) {
            if (typeList.contains(1)) {//按时间最新排序
                queryWrapper.orderByDesc("createTime");
            }
            if (typeList.contains(2)) {//按时间最久排序
                queryWrapper.orderByAsc("createTime");
            }
            if (typeList.contains(3)) {//按发布商品最少
                queryWrapper.orderByAsc("releaseCommNum");
            }
            if (typeList.contains(4)) {//按发布商品最多
                queryWrapper.orderByDesc("releaseCommNum");
            }
            if (typeList.contains(5)) {//按销售商品最多
                queryWrapper.orderByDesc("soldCommNum");
            }
            if (typeList.contains(6)) {//按销售商品最少
                queryWrapper.orderByAsc("soldCommNum");
            }
        }
        Page<UserBean> page = new Page<>(evt.getQueryPage(), evt.getQuerySize());
        Page<UserBean> modelPage = userMapper.selectPage(page, queryWrapper);
        if (modelPage.getRecords() == null) {
            return new ServiceResp().error("can not find relative books");
        }
        return new ServiceResp().success(modelPage);
    }

    /**
     * 查看订单
     */
    public ServiceResp queryOrder(HttpServletRequest request, QueryOrderEvt evt) {
        String operatorNo = (String) request.getAttribute("userNo");
        evt.setOperatorNo(operatorNo);
        Page<QueryOrderModel> page = new Page<>(evt.getQueryPage(), evt.getQuerySize());
        Page<QueryOrderModel> modelPage = orderMapper.queryOrder(evt, page);
        if (modelPage.getRecords() == null) {
            return new ServiceResp().error("can not find relative orders");
        }
        DecimalFormat df = new DecimalFormat();
        df.applyPattern("#,##0.00");
        for (QueryOrderModel model : page.getRecords()) {
            if (model.getPrice() == null) {//$9.00 <=9.00
                model.setPrice(0.0);
            }
            BigDecimal decimalPrice = new BigDecimal(model.getPrice());
            StringBuffer sb1 = new StringBuffer(df.format(decimalPrice));
            sb1.insert(0, "$");
            model.setTurePrice(new String(sb1));
            //只在订单为“申请取消订单”情况下展示申请取消理由，以及申请取消人，否则为‘无’
            if (!"申请取消".equals(model.getOrderStatus())) {
                model.setCancelOperatorRole("无");
                model.setCancelOperator("无");
                model.setCancelReason("无");
            }
        }
        return new ServiceResp().success(modelPage);
    }

    /**
     * 加入购物车
     */
    public ServiceResp addShoppingCart(HttpServletRequest request, AddShoppingCartEvt evt) {
        String buyerNo = (String) request.getAttribute("userNo");
        String buyerEmail = (String) request.getAttribute("userEmail");
        UserBean userInfo = userMapper.queryUserByNo(buyerNo);
        if (userInfo == null) {
            return new ServiceResp().error("user doesn't exist");
        }
        CommodityBean commodityInfo = commodityMapper.queryOneRecord(evt.getBookNo());
        if (commodityInfo == null) {
            return new ServiceResp().error("commodity doesn't exist");
        }
        QueryWrapper<CommPicBean> queryWrapper1 = new QueryWrapper<>();
        queryWrapper1.eq("bookNo", commodityInfo.getBookNo()).eq("status", "E");
        List<CommPicBean> picUrlList = commPicMapper.selectList(queryWrapper1);
        if (picUrlList == null) {
            return new ServiceResp().error("commodity picture record doesn't exist");
        }
        //若购买数量超过库存
        if (evt.getNum() > commodityInfo.getBookStock()) {
            return new ServiceResp().error("the commodity is out of the purchase limit");
        }
        //加入t_shoppingCart表
        ShoppingCartBean shoppingCartBean = new ShoppingCartBean();
        shoppingCartBean.setId(StringUtils.replace(UUID.randomUUID().toString(), "-", ""));
        shoppingCartBean.setBuyerNo(buyerNo);
        shoppingCartBean.setBuyerEmail(buyerEmail);
        shoppingCartBean.setBookNo(commodityInfo.getBookNo());
        shoppingCartBean.setBookName(commodityInfo.getBookName());
        shoppingCartBean.setSellerName(commodityInfo.getSellerName());
        shoppingCartBean.setSellerNo(commodityInfo.getSellerNo());
        shoppingCartBean.setBookPrice(commodityInfo.getBookPrice());
        //存入商品图片
        if (picUrlList != null) {
            for (int i = 1; i < picUrlList.size() + 1; i++) {
                String bookUrlName = "bookPicUrl";
                bookUrlName += i;
                if (bookUrlName.equals("bookPicUrl1")) {
                    shoppingCartBean.setBookPicUrl1(picUrlList.get(i - 1).getPictureUrl());
                } else if (bookUrlName.equals("bookPicUrl2")) {
                    shoppingCartBean.setBookPicUrl2(picUrlList.get(i - 1).getPictureUrl());
                } else if (bookUrlName.equals("bookPicUrl3")) {
                    shoppingCartBean.setBookPicUrl3(picUrlList.get(i - 1).getPictureUrl());
                } else {
                    shoppingCartBean.setBookPicUrl4(picUrlList.get(i - 1).getPictureUrl());
                }
            }
        }
        //先判断购物车中是否有同一本书，若有则num++
        QueryWrapper<ShoppingCartBean> queryWrapper=new QueryWrapper<>();
        queryWrapper.eq("bookNo",evt.getBookNo());
        queryWrapper.eq("status","E");
        ShoppingCartBean bean =shoppingCartMapper.selectOne(queryWrapper);
        if(queryWrapper!=null){
            shoppingCartBean.setNum(evt.getNum()+1);
        }
        shoppingCartBean.setBookTag(commodityInfo.getBookTag());
        shoppingCartBean.setNewOldDegree(commodityInfo.getNewOldDegree());
        shoppingCartBean.setStatus("E");
        shoppingCartBean.setUpdateUser(buyerEmail);
        shoppingCartBean.setCreateUser(buyerEmail);
        int result = shoppingCartMapper.insert(shoppingCartBean);
        if (result != 1) {
            return new ServiceResp().error("add to shopping cart error");
        } else return new ServiceResp().success("add to shopping cart successfully");
    }

    /**
     * 查询购物车
     */
    public ServiceResp queryShoppingCart(HttpServletRequest request) {
        String buyerNo = (String) request.getAttribute("userNo");
        QueryWrapper<ShoppingCartBean> queryWrapper = new QueryWrapper<>();
        queryWrapper.select();
        queryWrapper.eq("buyerNo", buyerNo);
        queryWrapper.eq("status", "E");
        queryWrapper.orderByAsc("buyerNo");
        List<ShoppingCartBean> returnModel = shoppingCartMapper.selectList(queryWrapper);
        if (returnModel == null) {
            return new ServiceResp().error("can not find relative shopping cart list");
        }
        QueryShoppingCartModel queryShoppingCartModel=new QueryShoppingCartModel();
        Double totalPrice=0.0;//计算所有商品总价
        Map<String, List<ShoppingCartBean>> shoppingCartMap = new HashMap<>();
        String sellerName = "";
        List<ShoppingCartBean> list = new ArrayList<>();
        for (ShoppingCartBean bean : returnModel) {
            //计算并加入总价
            totalPrice+=bean.getBookPrice()*bean.getNum();
            if (StringUtils.isBlank(sellerName)) {
                sellerName = bean.getSellerName();
            }
            if (!sellerName.equals(bean.getSellerName())) {//若商家不同
                sellerName = bean.getSellerName();
                list = new ArrayList<>();//指向新list
            }
            list.add(bean);
            shoppingCartMap.put(sellerName, list);
        }
        //拼接成字符格式
        DecimalFormat df = new DecimalFormat();
        df.applyPattern("#,##0.00");
        BigDecimal decimalPrice = new BigDecimal(totalPrice);
        StringBuffer sb1 = new StringBuffer(df.format(decimalPrice));
        sb1.insert(0, "$");
        queryShoppingCartModel.setSum(new String(sb1));
        queryShoppingCartModel.setCartList(shoppingCartMap);
        return new ServiceResp().success(queryShoppingCartModel);
    }

    /**
     * 修改购物车内商品信息(数量、删除)
     */
    public ServiceResp editShoppingCart(HttpServletRequest request, EditShoppingCartEvt evt) {
        ShoppingCartBean shoppingCartInfo = shoppingCartMapper.selectById(evt.getId());
        if (shoppingCartInfo == null) {
            return new ServiceResp().error("record doesn't exist");
        }
        QueryWrapper<CommodityBean> queryWrapper = new QueryWrapper<CommodityBean>();
        queryWrapper
                .eq("status", "E")
                .eq("bookNo", shoppingCartInfo.getBookNo());
        CommodityBean commodityInfo = commodityMapper.selectOne(queryWrapper);
        if (commodityInfo == null) {
            return new ServiceResp().error("this book had already been off shelved");
        }
        //去除购物车商品
        if ("D".equals(evt.getStatus())) {
            shoppingCartInfo.setStatus("D");
        }
        //更新商品数量
        if (evt.getNum() != null) {
            if (commodityInfo.getBookStock() == 0) {
                return new ServiceResp().error("The book has been snapped up");
            }
            if (commodityInfo.getBookStock() < evt.getNum()) {
                return new ServiceResp().error("the number of book you purchase is bigger than stock!");
            }
            shoppingCartInfo.setNum(evt.getNum());
        }
        shoppingCartInfo.setUpdateUser((String) request.getAttribute("userEmail"));
        int result = shoppingCartMapper.updateById(shoppingCartInfo);
        if (result != 1) {
            return new ServiceResp().error("edit shopping cart error");
        }
        return new ServiceResp().success("edit shopping cart successfully");
    }

    /**
     * 下单商品
     */
    public ServiceResp placeOrder(HttpServletRequest request, PlaceOrderEvt evt) {
        String buyerNo = (String) request.getAttribute("userNo");
        //查询商品状况
        CommodityBean commodityInfo = commodityMapper.queryOneRecord(evt.getBookNo());
        if (commodityInfo.getBookStock() == 0) {
            return new ServiceResp().error("this book has been out of the shelve just now");
        }
        int currentStock = commodityInfo.getBookStock() - evt.getNum();
        int currentSell = commodityInfo.getBookSale() + evt.getNum();
        if (currentStock < 0) {
            return new ServiceResp().error("the goods you purchased: " + commodityInfo.getBookName() + "  has exceeded the actual quantity");
        }
        commodityInfo.setBookStock(currentStock);
        commodityInfo.setBookSale(currentSell);
        OrderBean orderBean = new OrderBean();
        BeanUtils.copyProperties(evt, orderBean);
        orderBean.setBuyerNo(buyerNo);
        orderBean.setStatus("E");
        orderBean.setBuyerDisplay(0);
        orderBean.setSellerDisplay(0);
        orderBean.setBookName(commodityInfo.getBookName());
        orderBean.setOrderNo(StringUtils.replace(UUID.randomUUID().toString(), "-", ""));
        orderBean.setOrderStatus(0);
        orderBean.setCreateUser((String) request.getAttribute("userEmail"));
        orderBean.setUpdateUser((String) request.getAttribute("userEmail"));
        int result2 = orderMapper.insert(orderBean);
        if (result2 != 1) {
            return new ServiceResp().error("place order fail");
        }
        int result1 = commodityMapper.updateById(commodityInfo);
        if (result1 != 1) {
            return new ServiceResp().error("place order fail, when changing inventory quantity");
        }
        return new ServiceResp().success(orderBean);
    }


    /**
     * 购物车内下单(批量下单)
     */
    public ServiceResp placeCartOrder(PlaceCartOrderEvt evt) {
        String[] idArray = evt.getIdArray();
        List<String> responseList = new ArrayList<>();
        for (String id : idArray) {
            String responseMsg = "";
            ShoppingCartBean shoppingCartInfo = shoppingCartMapper.selectById(id);
            String bookName = shoppingCartInfo.getBookName();
            //查询商品状况
            CommodityBean commodityInfo = commodityMapper.queryOneRecord(shoppingCartInfo.getBookNo());
            if (commodityInfo == null) {
                responseMsg = "your book: " + bookName + " place order failed, the reason is: book record doesn't exist";
                responseList.add(responseMsg);
                continue;
            }
            if (commodityInfo.getBookStock() == 0) {
                responseMsg = "your book: " + bookName + " place order failed, the reason is: this book has been out of the shelve just now";
                responseList.add(responseMsg);
                continue;
            }
            int currentStock = commodityInfo.getBookStock() - shoppingCartInfo.getNum();
            int currentSell = commodityInfo.getBookSale() + shoppingCartInfo.getNum();
            if (currentStock < 0) {
                responseMsg = "your book: " + bookName + " place order failed, the reason is: this order has exceeded the actual quantity";
                responseList.add(responseMsg);
                continue;
            }
            commodityInfo.setBookStock(currentStock);
            commodityInfo.setBookSale(currentSell);
            //更新stock、sale
            int result1 = commodityMapper.updateById(commodityInfo);
            if (result1 != 1) {
                responseMsg = "your book: " + bookName + " place order failed, the reason is: place order fail, when changing inventory quantity";
                responseList.add(responseMsg);
                continue;
            }
            OrderBean orderBean = new OrderBean();
            BeanUtils.copyProperties(evt, orderBean);
            //计算同一种书本的总价,并存入数据库
            Double price = commodityInfo.getBookPrice() * shoppingCartInfo.getNum();
            orderBean.setPrice(price);
            orderBean.setNum(shoppingCartInfo.getNum());
            //存入sellerNo，bookNo
            orderBean.setBookNo(shoppingCartInfo.getBookNo());
            orderBean.setSellerNo(shoppingCartInfo.getSellerNo());
            //存入其他必存
            orderBean.setBuyerNo(shoppingCartInfo.getBuyerNo());
            orderBean.setStatus("E");
            orderBean.setBookName(shoppingCartInfo.getBookName());
            orderBean.setOrderNo(StringUtils.replace(UUID.randomUUID().toString(), "-", ""));
            orderBean.setOrderStatus(0);
            orderBean.setSellerDisplay(0);
            orderBean.setBuyerDisplay(0);
            orderBean.setCreateUser(shoppingCartInfo.getBuyerEmail());
            orderBean.setUpdateUser(shoppingCartInfo.getBuyerEmail());
            int result = orderMapper.insert(orderBean);
            if (result != 1) {
                //需要变更回去？
                commodityInfo.setBookStock(currentStock + shoppingCartInfo.getNum());
                commodityInfo.setBookSale(currentSell - shoppingCartInfo.getNum());
                commodityMapper.updateById(commodityInfo);
                //记错
                responseMsg = "your book: " + bookName + " place order failed, the reason is: failed when add order to the database";
                responseList.add(responseMsg);
                continue;
            }
            //删除t_shoppingCart的记录
            shoppingCartInfo.setStatus("D");
            int result2 = shoppingCartMapper.updateById(shoppingCartInfo);
            if (result2 != 1) {
                responseMsg = "your book: " + bookName + " place order failed, the reason is: place order fail, when deleting shopping cart record";
                responseList.add(responseMsg);
                continue;
            }
        }
        if (responseList.size() == 0) {
            return new ServiceResp().success("place orders successfully!");
        }
        ServiceResp resp = new ServiceResp();
        resp.setBody(responseList);
        ServiceHeader header = new ServiceHeader();
        header.setRespCode(-1);
        header.setRespMsg("there are some orders failed");
        resp.setHead(header);
        return resp;
    }

    /**
     * 取消订单
     */
    public ServiceResp cancelOrder(HttpServletRequest request, CancelOrderEvt evt) {
        String operatorEmail = (String) request.getAttribute("userEmail");
        String operatorNo = (String) request.getAttribute("userNo");
        String operatorName = (String) request.getAttribute("userName");
        OrderBean orderInfo = orderMapper.selectById(evt.getOrderNo());
        if (orderInfo == null) {
            return new ServiceResp().error("order record not found");
        }
        //更新数据库订单记录
        orderInfo.setUpdateUser(operatorEmail);
        orderInfo.setCancelOperator(operatorEmail);
        if (evt.getOperatorRole() == 1) {
            //角色为商家
            orderInfo.setCancelOperatorRole(1);
            orderInfo.setOrderStatus(4);
        } else {
            //角色为买家
            orderInfo.setCancelOperatorRole(0);
            orderInfo.setOrderStatus(3);
        }
        orderInfo.setCancelReason(evt.getCancelReason());
        int result = orderMapper.updateById(orderInfo);
        if (result != 1) {
            return new ServiceResp().error("updating order record failed");
        }
        //增加图书库存、处理用户的发布商品数量
        CommodityBean bookInfo = commodityMapper.selectById(orderInfo.getBookNo());
        if (bookInfo == null) {
            return new ServiceResp().error("book info not found");
        }
        int currentStock = bookInfo.getBookStock() + 1;
        bookInfo.setBookStock(currentStock);
        if (currentStock == 1) {
            UserBean sellerInfo = userMapper.selectById(orderInfo.getSellerNo());
            if (sellerInfo == null) {
                return new ServiceResp().error("seller info not found");
            }
            //bookStock+1后等于1时ReleaseCommNum+1
            sellerInfo.setReleaseCommNum(sellerInfo.getReleaseCommNum() + 1);
            int result2 = userMapper.updateById(sellerInfo);
            if (result2 != 1) {
                return new ServiceResp().error("updating ReleaseCommNum failed");
            }
        }
        int result3 = commodityMapper.updateById(bookInfo);
        if (result3 != 1) {
            return new ServiceResp().error("updating bookStock failed");
        }
        //发送邮件通知
        SendCancelOrderMsgEvt msgEvt = new SendCancelOrderMsgEvt();
        msgEvt.setCancelReason(evt.getCancelReason());
        msgEvt.setBookName(bookInfo.getBookName());
        msgEvt.setOperatorName(operatorName);
        if (evt.getOperatorRole() == 0) {//买家取消订单
            //发送邮件给卖家
            msgEvt.setOperatorRole(0);
            msgEvt.setUserEmail(bookInfo.getCreateUser());
            messageService.sendCancelOrderMsg(msgEvt);
            return new ServiceResp().success("apply cancel order request successfully, please wait patiently for the seller to handle");
        } else {
            //发送邮件给买家
            msgEvt.setOperatorRole(1);
            msgEvt.setUserEmail(orderInfo.getCreateUser());
            messageService.sendCancelOrderMsg(msgEvt);
            return new ServiceResp().success("cancel the order successfully");
        }
    }

    /**
     * 商家确认订单发货
     */
    public ServiceResp deliverGood(HttpServletRequest request, DeliverGoodEvt evt) {
        String sellerEmail = (String) request.getAttribute("userEmail");
        OrderBean orderInfo = orderMapper.selectById(evt.getOrderNo());
        if (orderInfo == null) {
            return new ServiceResp().error("order record not found");
        }
        CommodityBean bookInfo = commodityMapper.selectById(orderInfo.getBookNo());
        if (bookInfo == null) {
            return new ServiceResp().error("book record not found");
        }
        //增加销量、减少商家已发布商品数量
        int currentStock = bookInfo.getBookStock();
        int currentSale = bookInfo.getBookSale() + orderInfo.getNum();
        bookInfo.setBookSale(currentSale);
        //若此时该商品库存以为0,表示该种类商品已下架(更新user表),并将商品status=’D‘
        if (currentStock == 0) {
            UserBean sellerInfo = userMapper.selectById(bookInfo.getSellerNo());
            bookInfo.setStatus("D");
            if (sellerInfo == null) {
                return new ServiceResp().error("relative seller record not found");
            }
            if (sellerInfo.getReleaseCommNum() == null) {
                sellerInfo.setReleaseCommNum(0);
            }
            sellerInfo.setReleaseCommNum(sellerInfo.getReleaseCommNum() - 1);
            int result2 = userMapper.updateById(sellerInfo);
            if (result2 != 1) {
                return new ServiceResp().error("reduce ReleaseCommNum failed");
            }
        }
        int result = commodityMapper.updateById(bookInfo);
        if (result != 1) {
            return new ServiceResp().error("increase bookSale or update book off shelve failed");
        }
        //设置订单状态（已发货）
        orderInfo.setOrderStatus(1);
        int result3 = orderMapper.updateById(orderInfo);
        if (result3 != 1) {
            return new ServiceResp().error("Set order status (shipped) failed");
        }
        return new ServiceResp().success("deliver good successfully");
    }

    /**
     * 买家确认收货
     */
    public ServiceResp confirmReceipt(HttpServletRequest request, String orderNo) {
        String buyerNo = (String) request.getAttribute("userNo");
        OrderBean orderInfo = orderMapper.selectById(orderNo);
        if (orderInfo == null) {
            return new ServiceResp().error("book info record not found");
        }
        UserBean buyerInfo = userMapper.selectById(buyerNo);
        if (buyerInfo == null) {
            return new ServiceResp().error("buyer info record not found");
        }
        //更新订单状态
        orderInfo.setUpdateUser(buyerInfo.getUserEmail());
        orderInfo.setOrderStatus(2);
        int result = orderMapper.updateById(orderInfo);
        if (result != 1) {
            return new ServiceResp().error("update order status failed");
        }
        //更新商家销量
        UserBean sellerInfo = userMapper.selectById(orderInfo.getSellerNo());
        sellerInfo.setSoldCommNum(sellerInfo.getSoldCommNum() + 1);
        int result2 = userMapper.updateById(sellerInfo);
        if (result2 != 1) {
            return new ServiceResp().error("update seller SoldCommNum failed");
        }
        return new ServiceResp().success("confirm receipt successfully");
    }

    /**
     * 卖家处理"取消订单申请"
     */
    public ServiceResp processingCancellationRequest(HttpServletRequest request, ProcessingCancellationRequestEvt evt) {
        String operator = (String) request.getAttribute("userEmail");
        OrderBean orderInfo = orderMapper.selectById(evt.getOrderNo());
        if (orderInfo == null) {
            return new ServiceResp().error("order information not found");
        }
        if (evt.getOperation() == 0) {//进行同意操作
            orderInfo.setOrderStatus(4);
        } else {//进行拒绝申请操作
            orderInfo.setOrderStatus(1);
        }
        orderInfo.setUpdateUser(operator);
        //更新数据
        int result = orderMapper.updateById(orderInfo);
        if (result != 1) {
            return new ServiceResp().error("update order status error");
        }
        //发送邮件通知
        OperateCancelOperationMsgEvt msgEvt = new OperateCancelOperationMsgEvt();
        msgEvt.setOperation(evt.getOperation());
        msgEvt.setBookName(orderInfo.getBookName());
        msgEvt.setOperator(operator);
        msgEvt.setUserEmail(orderInfo.getCreateUser());
        if (evt.getOperation() == 1) {//自定义拒绝取消邮件
            msgEvt.setReason(evt.getRefuseReason());
        }
        messageService.sendCancelOperationMsg(msgEvt);
        return new ServiceResp().success("update order status successfully");
    }

    /**
     * 删除订单记录
     */
    public ServiceResp deleteOrderRecord(HttpServletRequest request, DeleteOrderRecordEvt evt) {
        OrderBean orderInfo = orderMapper.selectById(evt.getOrderNo());
        if (orderInfo == null) {
            return new ServiceResp().error("order information not found");
        }
        //查看用户角色
        if (evt.getOperatorRole() == 0) {//买家
            orderInfo.setBuyerDisplay(1);
        } else if (evt.getOperatorRole() == 1) {//卖家
            orderInfo.setSellerDisplay(1);
        } else {//管理员
            orderInfo.setStatus("D");
        }
        orderInfo.setUpdateUser((String) request.getAttribute("userNo"));
        int result = orderMapper.updateById(orderInfo);
        if (result != 0) {
            return new ServiceResp().error("update order status error");
        }
        return new ServiceResp().success("update order status successfully");
    }
}

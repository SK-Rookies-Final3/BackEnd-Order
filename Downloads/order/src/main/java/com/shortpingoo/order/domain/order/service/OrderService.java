package com.shortpingoo.order.domain.order.service;

import com.shortpingoo.order.domain.order.dto.OrderRequest;
import com.shortpingoo.order.domain.order.dto.OrderResponse;

import java.util.List;

public interface OrderService {

    List<OrderResponse> createOrder(int userId, OrderRequest orderRequest);
    //List<OrderResponse> getOrderByStoreId(int storeId, int userId);
    //OrderResponse getOrderDetails(int orderCode, int userId);

}
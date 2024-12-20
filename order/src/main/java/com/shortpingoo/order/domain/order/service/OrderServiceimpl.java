package com.shortpingoo.order.domain.order.service;

import com.shortpingoo.order.db.order.Order;
import com.shortpingoo.order.db.order.OrderRepository;
import com.shortpingoo.order.db.orderitem.OrderItem;
import com.shortpingoo.order.db.orderitem.OrderItemRepository;
import com.shortpingoo.order.domain.order.dto.OrderAllResponse;
import com.shortpingoo.order.domain.order.dto.OrderRequest;
import com.shortpingoo.order.domain.order.dto.OrderResponse;
import com.shortpingoo.order.domain.order.dto.StatusRequest;
import com.shortpingoo.order.domain.orderitem.dto.OrderItemRequest;
import com.shortpingoo.order.domain.orderitem.dto.OrderItemResponse;
import com.shortpingoo.order.domain.orderitem.dto.StockUpdateRequest;
import com.shortpingoo.order.domain.pay.service.PayService;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.response.IamportResponse;
import com.siot.IamportRestClient.response.Payment;
import jakarta.persistence.EntityNotFoundException;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderServiceimpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ModelMapper modelMapper;
    private final RestTemplate restTemplate;
    private final PayService payService;

    @Autowired
    public OrderServiceimpl(OrderRepository orderRepository, ModelMapper modelMapper,
                            OrderItemRepository orderItemRepository, RestTemplate restTemplate,
                            PayService payService) {
        this.orderRepository = orderRepository;
        this.modelMapper = modelMapper;
        this.orderItemRepository = orderItemRepository;
        this.restTemplate = restTemplate;
        this.payService = payService;
    }

    @Value("${brand.api.url}")
    private String brandApiUrl;

    @Value("${stock.api.url}")
    private String stockApiUrl;

    // 주문 생성
    @Transactional
    @Override
    public List<OrderResponse> createOrder(int userId, OrderRequest orderRequest, String impUid)
            throws IamportResponseException, IOException  {
        // 주문 데이터 저장
        Order order = modelMapper.map(orderRequest, Order.class);
        order.setUserId(userId);
        order.setOrderDate(LocalDateTime.now());

        // 결제 확인
        IamportResponse<Payment> payResponse = payService.verifyAndSavePayment(userId, impUid); // 결제 확인

        if (payResponse == null ) {
            throw new RuntimeException("결제 실패");
        }

        // 주문 저장
        order.setStatus(1); // 주문 완료 상태
        orderRepository.save(order);

        // OrderItem 저장 및 응답 생성
        List<OrderResponse> orderResponses = new ArrayList<>();
        for (OrderItemRequest itemRequest : orderRequest.getOrderItemList()) {
            OrderItem orderItem = modelMapper.map(itemRequest, OrderItem.class);
            orderItem.setOrder(order); // Order와 연관 설정
            orderItemRepository.save(orderItem);

            // 상품 재고 조회
            int productCode = itemRequest.getProductCode(); // 상품 코드
            int orderStock = itemRequest.getStock(); // 주문 수량

            // 재고 조회 API 호출 (현재 재고 조회)
            int currentStock = restTemplate.getForObject(stockApiUrl + "/{productCode}", Integer.class, productCode);

            // 재고가 부족한 경우 예외 처리
            if (currentStock < orderStock) {
                throw new RuntimeException("재고가 부족합니다. 상품 코드: " + productCode);
            }

            // 재고 감소 (주문 수량만큼)
            int updatedStock = currentStock - orderStock;

            // 재고 업데이트 요청 (PATCH로 수정)
            StockUpdateRequest stockUpdateRequest = new StockUpdateRequest(updatedStock);

            // HTTP PATCH 요청 보내기
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "X-User-Id"); // JWT 토큰 추가
            HttpEntity<StockUpdateRequest> requestEntity = new HttpEntity<>(stockUpdateRequest);

            // PATCH 요청 보내기
            restTemplate.exchange(stockApiUrl + "/{productCode}",
                    HttpMethod.PATCH, requestEntity, Void.class, productCode);

            // OrderItem 기반 OrderResponse 생성
            OrderResponse orderResponse = modelMapper.map(orderItem, OrderResponse.class);
            orderResponse.setCode(order.getCode());
            orderResponse.setUserId(userId);
            orderResponse.setStatus(order.getStatus());

            orderResponses.add(orderResponse);
        }

        return orderResponses;
    }

    // 가게별 주문 전체 내역 조회
    @Override
    @Transactional
    public List<OrderAllResponse> getOrdersByOwner(int userId) {
        // 사용자 소유 상품 조회 (Brand API 호출)
        List<Map<String, Object>> products = fetchProductsByOwner(userId);

        if (products.isEmpty()) {
            return Collections.emptyList();
        }

        // 상품 코드 추출
        List<String> productCodes = products.stream()
                .map(product -> String.valueOf(product.get("code")))
                .collect(Collectors.toList());

        // 상품 코드에 해당하는 OrderItem들을 조회
        List<OrderItem> orderItems = orderItemRepository.findByProductCodeIn(productCodes);

        // OrderItem을 기반으로 해당 Order를 조회
        List<Order> orders = orderItems.stream()
                .map(OrderItem::getOrder)
                .distinct()
                .collect(Collectors.toList());

        // 주문 데이터를 OrderAllResponse로 변환
        return orders.stream()
                .map(order -> {
                    // 현재 Order에 연관된 OrderItem들을 조회
                    List<OrderItem> relatedOrderItems = orderItemRepository.findByOrder(order);

                    // OrderItem들을 OrderItemResponse로 변환
                    List<OrderItemResponse> orderItemResponses = relatedOrderItems.stream()
                            .map(orderItem -> modelMapper.map(orderItem, OrderItemResponse.class))
                            .collect(Collectors.toList());

                    // OrderAllResponse 생성
                    return OrderAllResponse.builder()
                            .code(order.getCode()) // 주문 코드
                            .userId(order.getUserId())
                            .status(order.getStatus())
                            .orderDate(order.getOrderDate())
                            .orderItems(orderItemResponses)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // 사용자(owner)의 상품 목록을 Brand API로 조회 (헤더 사용)
    private List<Map<String, Object>> fetchProductsByOwner(int ownerId) {
        String url = brandApiUrl + "Rest";
        System.out.println("/////brandApiUrl/////");
        System.out.println(brandApiUrl);
        // 요청 헤더 생성
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(ownerId)); // 헤더에 ownerId 추가
        System.out.println("/////ownerId/////");
        System.out.println(ownerId);
        // HttpEntity에 헤더 추가
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        System.out.println("/////entity/////");
        System.out.println(entity);
        try {
            System.out.println("/////response start/////");

            // GET 요청에 헤더 포함
            ResponseEntity<List> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    List.class
            );
            System.out.println("/////fetchProductsByOwner/////");
            System.out.println(response.getBody());
            return response.getBody();
        } catch (Exception e) {
            System.err.println("Brand API 호출 실패: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // 사용자(Client)의 본인 주문 전체 내역 조회
    @Override
    public List<OrderAllResponse> getOrderDetails(int userId) {
        // 여러 주문을 userId로 조회
        List<Order> orders = orderRepository.findByUserId(userId);

        // 주문 아이템을 조회하고 OrderItemResponse로 변환
        return orders.stream().map(order -> {
            List<OrderItem> orderItems = orderItemRepository.findByOrder(order);
            List<OrderItemResponse> orderItemResponses = orderItems.stream()
                    .map(orderItem -> modelMapper.map(orderItem, OrderItemResponse.class))
                    .collect(Collectors.toList());

            return OrderAllResponse.builder()
                    .code(order.getCode())
                    .userId(order.getUserId())
                    .status(order.getStatus())
                    .orderDate(order.getOrderDate())
                    .orderItems(orderItemResponses)
                    .build();
        }).collect(Collectors.toList());
    }

    // 사용자(owner)의 주문 상태 변경
    @Override
    public OrderResponse updateOrderStatus(int orderCode, int userId, StatusRequest statusRequest) {
        Optional<Order> optionalOrder = orderRepository.findByCode(orderCode);

        if (!optionalOrder.isPresent()) {
            throw new RuntimeException("Order not found with code " + orderCode);
        }

        Order order = optionalOrder.get();

        order.setStatus(statusRequest.getStatus());
        Order updatedOrder = orderRepository.save(order);
        OrderResponse orderResponse = modelMapper.map(updatedOrder, OrderResponse.class);

        return orderResponse;
    }
}

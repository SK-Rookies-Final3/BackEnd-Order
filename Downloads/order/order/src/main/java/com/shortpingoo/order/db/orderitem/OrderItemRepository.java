package com.shortpingoo.order.db.orderitem;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository  extends JpaRepository<OrderItem, Integer> {
}

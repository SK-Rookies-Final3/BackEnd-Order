package com.shortpingoo.order.db.orderitem;

import com.shortpingoo.order.db.order.Order;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private int id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_code", nullable = false)
    private Order order;

    @Column(name = "product_code", nullable = false)
    private int productCode;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "clothes_size", length = 20)
    private String clothesSize;

    @Column(name = "shoes_size", length = 20)
    private String shoesSize;

    @Column(name = "color", length = 20, nullable = false)
    private String color;

}
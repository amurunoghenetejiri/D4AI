package com.d4tech.d4ai.appcontrol.adapters

import com.d4tech.d4ai.appcontrol.AppControlManager

class JumiaAdapter(private val control: AppControlManager) {
    fun searchCheapestAddCart(product: String) =
        control.executeNatural("open jumia search cheapest $product add to cart")
}

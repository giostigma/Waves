package com.wavesplatform.matcher.model

import cats.implicits._
import com.wavesplatform.db.{OrderIdsCodec, PortfolioCodec, SubStorage}
import com.wavesplatform.matcher.model.Events.{Event, OrderAdded, OrderCanceled, OrderExecuted}
import com.wavesplatform.matcher.model.LimitOrder.{Filled, OrderStatus}
import com.wavesplatform.state2._
import org.iq80.leveldb.DB
import play.api.libs.json.Json
import scorex.transaction.AssetAcc
import scorex.transaction.assets.exchange.{AssetPair, Order}
import scorex.utils.ScorexLogging

trait OrderHistory {
  def orderAccepted(event: OrderAdded): Unit

  def orderExecuted(event: OrderExecuted): Unit

  def orderCanceled(event: OrderCanceled)

  def orderStatus(id: String): OrderStatus

  def orderInfo(id: String): OrderInfo

  def openVolume(assetAcc: AssetAcc): Long

  def openVolumes(address: String): Option[Map[String, Long]]
  def ordersByPairAndAddress(assetPair: AssetPair, address: String): Set[String]

  def getAllOrdersByAddress(address: String): Stream[String]
  def deleteOrder(assetPair: AssetPair, address: String, orderId: String): Boolean

  def order(id: String): Option[Order]

  def openPortfolio(address: String): OpenPortfolio
}

case class OrderHistoryImpl(db: DB) extends SubStorage(db: DB, "matcher") with OrderHistory with ScorexLogging {


  val MaxOrdersPerAddress = 1000
  val MaxOrdersPerRequest = 100

  private val OrdersPrefix = "orders".getBytes(Charset)
  private val OrdersInfoPrefix = "infos".getBytes(Charset)
  private val PairToOrdersPrefix = "pairs".getBytes(Charset)
  private val AddressPortfolioPrefix = "portfolios".getBytes(Charset)

  def savePairAddress(assetPair: AssetPair, address: String, orderId: String): Unit = {
    val pairAddress = assetPairAddressKey(assetPair, address)
    get(makeKey(PairToOrdersPrefix, pairAddress)) match {
      case Some(valueBytes) =>
        val prev = OrderIdsCodec.decode(valueBytes).explicitGet().value
        var r = prev
        if (prev.length >= MaxOrdersPerAddress) {
          val (p1, p2) = prev.span(!orderStatus(_).isInstanceOf[LimitOrder.Cancelled])
          r = if (p2.isEmpty) p1 else p1 ++ p2.tail

        }
        put(makeKey(PairToOrdersPrefix, pairAddress), OrderIdsCodec.encode(r :+ orderId))
      case _ =>
        put(makeKey(PairToOrdersPrefix, pairAddress), OrderIdsCodec.encode(Array(orderId)))
    }
  }

  def saveOrderInfo(event: Event): Unit = {
    Events.createOrderInfo(event).foreach { case (orderId, oi) =>
      put(makeKey(OrdersInfoPrefix, orderId), orderInfo(orderId).combine(oi).jsonStr.getBytes(Charset))
      log.debug(s"Changed OrderInfo for: $orderId -> " + orderInfo(orderId))
    }
  }

  def openPortfolio(address: String): OpenPortfolio = {
    get(makeKey(AddressPortfolioPrefix, address)).map(PortfolioCodec.decode).map(_.explicitGet().value)
      .map(OpenPortfolio.apply).getOrElse(OpenPortfolio.empty)
  }

  def saveOpenPortfolio(event: Event): Unit = {
    Events.createOpenPortfolio(event).foreach { case (address, op) =>
      val key = makeKey(AddressPortfolioPrefix, address)
      val prev = get(key).map(PortfolioCodec.decode).map(_.explicitGet().value).map(OpenPortfolio.apply).getOrElse(OpenPortfolio.empty)
      val updatedPortfolios = prev.combine(op)
      put(key, PortfolioCodec.encode(updatedPortfolios.orders))
      log.debug(s"Changed OpenPortfolio for: $address -> " + updatedPortfolios.toString)
    }
  }

  def saveOrder(order: Order): Unit = {
    val key = makeKey(OrdersPrefix, order.idStr())
    if (get(key).isEmpty)
      put(key, order.jsonStr.getBytes(Charset))
  }

  def deleteFromOrders(orderId: String): Unit = {
    delete(makeKey(OrdersPrefix, orderId))
  }

  override def orderAccepted(event: OrderAdded): Unit = {
    val lo = event.order
    saveOrder(lo.order)
    saveOrderInfo(event)
    saveOpenPortfolio(event)
    savePairAddress(lo.order.assetPair, lo.order.senderPublicKey.address, lo.order.idStr())
  }

  override def orderExecuted(event: OrderExecuted): Unit = {
    saveOrder(event.submitted.order)
    savePairAddress(event.submitted.order.assetPair, event.submitted.order.senderPublicKey.address, event.submitted.order.idStr())
    saveOrderInfo(event)
    saveOpenPortfolio(OrderAdded(event.submittedExecuted))
    saveOpenPortfolio(event)
  }

  override def orderCanceled(event: OrderCanceled): Unit = {
    saveOrderInfo(event)
    saveOpenPortfolio(event)
  }

  override def orderInfo(id: String): OrderInfo =
    get(makeKey(OrdersInfoPrefix, id)).map(Json.parse).flatMap(_.validate[OrderInfo].asOpt).getOrElse(OrderInfo.empty)

  override def order(id: String): Option[Order] = {
    import scorex.transaction.assets.exchange.OrderJson.orderFormat
    get(makeKey(OrdersPrefix, id)).map(b => new String(b, Charset)).map(Json.parse).flatMap(_.validate[Order].asOpt)
  }

  override def orderStatus(id: String): OrderStatus = {
    orderInfo(id).status
  }

  override def openVolume(assetAcc: AssetAcc): Long = {
    val asset = assetAcc.assetId.map(_.base58).getOrElse(AssetPair.WavesName)
    get(makeKey(AddressPortfolioPrefix, assetAcc.account.address)).map(PortfolioCodec.decode).map(_.explicitGet().value)
      .flatMap(_.get(asset)).map(math.max(0L, _)).getOrElse(0L)
  }

  override def openVolumes(address: String): Option[Map[String, Long]] = Option(p.addressToOrderPortfolio.get(address))

  override def ordersByPairAndAddress(assetPair: AssetPair, address: String): Set[String] = {
    val pairAddressKey = assetPairAddressKey(assetPair, address)
    get(makeKey(PairToOrdersPrefix, pairAddressKey)).map(OrderIdsCodec.decode).map(_.explicitGet().value)
      .map(_.takeRight(MaxOrdersPerRequest).toSet).getOrElse(Set())
  }

  override def getAllOrdersByAddress(address: String): Set[String] = {
    map(PairToOrdersPrefix).mapValues(OrderIdsCodec.decode).filter(_._1.endsWith(address)).values.flatMap(_.explicitGet().value).toSet
  }


  private def deleteFromOrdersInfo(orderId: String): Unit = delete(makeKey(OrdersInfoPrefix, orderId))

  private def deleteFromPairAddress(assetPair: AssetPair, address: String, orderId: String): Unit = {
    val pairAddress = assetPairAddressKey(assetPair, address)
    val key = makeKey(PairToOrdersPrefix, pairAddress)
    get(key) match {
      case Some(bytes) =>
        val prev = OrderIdsCodec.decode(bytes).explicitGet().value
        if (prev.contains(orderId)) put(key, OrderIdsCodec.encode(prev.filterNot(_ == orderId)))
      case _ =>
    }
  }

  override def deleteOrder(assetPair: AssetPair, address: String, orderId: String): Boolean = {
    orderStatus(orderId) match {
      case Filled | LimitOrder.Cancelled(_) =>
        deleteFromOrders(orderId)
        deleteFromOrdersInfo(orderId)
        deleteFromPairAddress(assetPair, address, orderId)
        true
      case _ =>
        false
    }
  }

  private def assetPairAddressKey(assetPair: AssetPair, address: String): String = assetPair.key + address
}

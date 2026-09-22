package com.example.app.feature.template

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*

/**
 * Template for Integration Tests across multiple layers.
 * 
 * Integration tests verify that multiple components work correctly together.
 * These tests use less mocking and test real interactions between layers.
 * 
 * Test Scope:
 * - ViewModel + UseCase integration
 * - UseCase + Repository integration
 * - Full vertical slice (ViewModel → UseCase → Repository)
 * - Multi-skill pipeline testing
 * 
 * Test Coverage Goals:
 * - End-to-end flows: 40%
 * - Error propagation: 25%
 * - State management: 20%
 * - Edge cases: 15%
 * 
 * When to Use Integration Tests:
 * - Testing data flow across layers
 * - Verifying error propagation
 * - Testing complex business workflows
 * - Validating caching/sync behavior
 * 
 * @see 01-architecture.md for architecture patterns
 * @see 11-testing.md for testing guidelines
 * @see 31-pipeline-patterns.md for pipeline testing
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// EXAMPLE DOMAIN MODELS
// ============================================================

data class User(
    val id: String,
    val name: String,
    val email: String,
)

data class Order(
    val id: String,
    val userId: String,
    val items: List<OrderItem>,
    val status: OrderStatus,
    val total: Double,
)

data class OrderItem(
    val productId: String,
    val name: String,
    val quantity: Int,
    val price: Double,
)

enum class OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}

// ============================================================
// EXAMPLE LAYERS
// ============================================================

// --- Repository Interface (Domain Layer) ---
interface UserRepository {
    suspend fun getUser(id: String): Result<User>
    suspend fun updateUser(user: User): Result<User>
}

interface OrderRepository {
    suspend fun getOrdersForUser(userId: String): Result<List<Order>>
    suspend fun createOrder(userId: String, items: List<OrderItem>): Result<Order>
    suspend fun cancelOrder(orderId: String): Result<Order>
}

// --- UseCase (Domain Layer) ---
class GetUserProfileUseCase(
    private val userRepository: UserRepository,
    private val orderRepository: OrderRepository,
) {
    data class UserProfile(
        val user: User,
        val recentOrders: List<Order>,
        val totalOrderValue: Double,
    )
    
    suspend operator fun invoke(userId: String): Result<UserProfile> {
        // Get user
        val userResult = userRepository.getUser(userId)
        if (userResult.isFailure) {
            return Result.failure(userResult.exceptionOrNull()!!)
        }
        
        val user = userResult.getOrThrow()
        
        // Get orders
        val ordersResult = orderRepository.getOrdersForUser(userId)
        val orders = ordersResult.getOrDefault(emptyList())
        
        // Calculate total
        val totalValue = orders
            .filter { it.status != OrderStatus.CANCELLED }
            .sumOf { it.total }
        
        return Result.success(
            UserProfile(
                user = user,
                recentOrders = orders.take(5),
                totalOrderValue = totalValue,
            )
        )
    }
}

class CreateOrderUseCase(
    private val userRepository: UserRepository,
    private val orderRepository: OrderRepository,
) {
    data class CreateOrderInput(
        val userId: String,
        val items: List<OrderItem>,
    )
    
    suspend operator fun invoke(input: CreateOrderInput): Result<Order> {
        // Validate user exists
        val userResult = userRepository.getUser(input.userId)
        if (userResult.isFailure) {
            return Result.failure(IllegalStateException("User not found"))
        }
        
        // Validate items
        if (input.items.isEmpty()) {
            return Result.failure(IllegalArgumentException("Order must have at least one item"))
        }
        
        // Create order
        return orderRepository.createOrder(input.userId, input.items)
    }
}

// --- ViewModel (Presentation Layer) ---
// ✅ CORRECT: Extends ViewModel, uses viewModelScope
// ❌ WRONG: Using GlobalScope (see Anti-patterns in AGENT_SUMMARY.md)
class ProfileViewModel(
    private val getUserProfileUseCase: GetUserProfileUseCase,
    private val createOrderUseCase: CreateOrderUseCase,
) : ViewModel() {
    
    // ✅ CORRECT: Private mutable, public immutable
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()
    
    // ✅ CORRECT: Channel for one-time events (NOT SharedFlow)
    private val _events = Channel<ProfileEvent>()
    val events = _events.receiveAsFlow()
    
    private var currentUserId: String? = null
    
    fun loadProfile(userId: String) {
        currentUserId = userId
        _state.update { it.copy(isLoading = true, error = null) }
        
        // ✅ CORRECT: viewModelScope (NOT GlobalScope)
        viewModelScope.launch {
            getUserProfileUseCase(userId)
                .onSuccess { profile ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            user = profile.user,
                            recentOrders = profile.recentOrders,
                            totalOrderValue = profile.totalOrderValue,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Unknown error",
                        )
                    }
                }
        }
    }
    
    fun createQuickOrder(items: List<OrderItem>) {
        val userId = currentUserId ?: return
        
        _state.update { it.copy(isCreatingOrder = true) }
        
        // ✅ CORRECT: viewModelScope (NOT GlobalScope)
        viewModelScope.launch {
            createOrderUseCase(CreateOrderUseCase.CreateOrderInput(userId, items))
                .onSuccess { order ->
                    _state.update {
                        it.copy(
                            isCreatingOrder = false,
                            recentOrders = listOf(order) + it.recentOrders,
                        )
                    }
                    _events.send(ProfileEvent.OrderCreated(order.id))
                }
                .onFailure { error ->
                    _state.update { it.copy(isCreatingOrder = false) }
                    _events.send(ProfileEvent.OrderFailed(error.message ?: "Failed"))
                }
        }
    }
    
    // ✅ CORRECT: @Immutable annotation for state class
    @Immutable
    data class ProfileState(
        val isLoading: Boolean = false,
        val isCreatingOrder: Boolean = false,
        val user: User? = null,
        val recentOrders: List<Order> = emptyList(),
        val totalOrderValue: Double = 0.0,
        val error: String? = null,
    )
    
    sealed interface ProfileEvent {
        data class OrderCreated(val orderId: String) : ProfileEvent
        data class OrderFailed(val message: String) : ProfileEvent
    }
}

// Note: Extension functions asStateFlow(), update(), receiveAsFlow() are from kotlinx.coroutines
// No need for custom implementations - use the official ones

// ============================================================
// INTEGRATION TEST SUITE
// ============================================================

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileIntegrationTest {
    
    // ========================================
    // Test Setup
    // ========================================
    
    private val testDispatcher = StandardTestDispatcher()
    
    // Mocked at the boundary (Repository layer)
    private lateinit var userRepository: UserRepository
    private lateinit var orderRepository: OrderRepository
    
    // Real implementations for integration
    private lateinit var getUserProfileUseCase: GetUserProfileUseCase
    private lateinit var createOrderUseCase: CreateOrderUseCase
    private lateinit var viewModel: ProfileViewModel
    
    // Test data
    private val testUser = User(
        id = "user-1",
        name = "John Doe",
        email = "john@example.com",
    )
    
    private val testOrders = listOf(
        Order(
            id = "order-1",
            userId = "user-1",
            items = listOf(OrderItem("prod-1", "Product 1", 2, 50.0)),
            status = OrderStatus.DELIVERED,
            total = 100.0,
        ),
        Order(
            id = "order-2",
            userId = "user-1",
            items = listOf(OrderItem("prod-2", "Product 2", 1, 75.0)),
            status = OrderStatus.SHIPPED,
            total = 75.0,
        ),
    )
    
    @BeforeEach
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        
        // Mock only the boundaries
        userRepository = mockk()
        orderRepository = mockk()
        
        // Real UseCases for integration testing
        getUserProfileUseCase = GetUserProfileUseCase(userRepository, orderRepository)
        createOrderUseCase = CreateOrderUseCase(userRepository, orderRepository)
        
        // Real ViewModel
        viewModel = ProfileViewModel(getUserProfileUseCase, createOrderUseCase)
    }
    
    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        clearAllMocks()
    }
    
    // ========================================
    // End-to-End Flow Tests (40%)
    // ========================================
    
    @Nested
    @DisplayName("Load Profile - End-to-End")
    inner class LoadProfileE2ETests {
        
        @Test
        fun `complete profile load flow works correctly`() = runTest {
            // Given - Setup repository responses
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(testOrders)
            
            // When - Trigger the flow from ViewModel
            viewModel.state.test {
                // Initial state
                val initial = awaitItem()
                assertThat(initial.isLoading).isFalse()
                assertThat(initial.user).isNull()
                
                // Trigger load
                viewModel.loadProfile("user-1")
                
                // Loading state
                val loading = awaitItem()
                assertThat(loading.isLoading).isTrue()
                
                // Success state
                advanceUntilIdle()
                val success = awaitItem()
                
                // Then - Verify complete state
                assertThat(success.isLoading).isFalse()
                assertThat(success.user).isEqualTo(testUser)
                assertThat(success.recentOrders).hasSize(2)
                assertThat(success.totalOrderValue).isEqualTo(175.0)  // 100 + 75
                
                cancelAndIgnoreRemainingEvents()
            }
            
            // Verify repository calls
            coVerify(exactly = 1) { userRepository.getUser("user-1") }
            coVerify(exactly = 1) { orderRepository.getOrdersForUser("user-1") }
        }
        
        @Test
        fun `create order flow updates profile correctly`() = runTest {
            // Given - Initial profile loaded
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(testOrders)
            
            val newOrder = Order(
                id = "order-3",
                userId = "user-1",
                items = listOf(OrderItem("prod-3", "New Product", 1, 50.0)),
                status = OrderStatus.PENDING,
                total = 50.0,
            )
            coEvery { orderRepository.createOrder("user-1", any()) } returns Result.success(newOrder)
            
            // When - Load profile then create order
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            viewModel.state.test {
                val beforeOrder = awaitItem()
                assertThat(beforeOrder.recentOrders).hasSize(2)
                
                // Create order
                viewModel.createQuickOrder(listOf(OrderItem("prod-3", "New Product", 1, 50.0)))
                
                // Creating state
                val creating = awaitItem()
                assertThat(creating.isCreatingOrder).isTrue()
                
                advanceUntilIdle()
                
                // After order created
                val afterOrder = awaitItem()
                assertThat(afterOrder.isCreatingOrder).isFalse()
                assertThat(afterOrder.recentOrders).hasSize(3)
                assertThat(afterOrder.recentOrders.first().id).isEqualTo("order-3")
                
                cancelAndIgnoreRemainingEvents()
            }
            
            // Verify event emitted
            viewModel.events.test {
                val event = awaitItem()
                assertThat(event).isInstanceOf(ProfileViewModel.ProfileEvent.OrderCreated::class.java)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
    
    // ========================================
    // Error Propagation Tests (25%)
    // ========================================
    
    @Nested
    @DisplayName("Error Propagation")
    inner class ErrorPropagationTests {
        
        @Test
        fun `user repository error propagates to ViewModel state`() = runTest {
            // Given
            coEvery { userRepository.getUser("user-1") } returns 
                Result.failure(IllegalStateException("User not found"))
            
            // When
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // Then
            val state = viewModel.state.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.error).contains("User not found")
            assertThat(state.user).isNull()
        }
        
        @Test
        fun `order repository error still shows user data`() = runTest {
            // Given - User succeeds, orders fail
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns 
                Result.failure(java.io.IOException("Network error"))
            
            // When
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // Then - User data shown, orders empty (graceful degradation)
            val state = viewModel.state.value
            assertThat(state.user).isEqualTo(testUser)
            assertThat(state.recentOrders).isEmpty()
            assertThat(state.error).isNull()  // Partial success
        }
        
        @Test
        fun `create order failure emits event`() = runTest {
            // Given
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(testOrders)
            coEvery { orderRepository.createOrder("user-1", any()) } returns 
                Result.failure(IllegalStateException("Insufficient inventory"))
            
            // Load profile first
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // When
            viewModel.events.test {
                viewModel.createQuickOrder(listOf(OrderItem("prod-1", "Product", 1, 10.0)))
                advanceUntilIdle()
                
                // Then
                val event = awaitItem()
                assertThat(event).isInstanceOf(ProfileViewModel.ProfileEvent.OrderFailed::class.java)
                assertThat((event as ProfileViewModel.ProfileEvent.OrderFailed).message)
                    .contains("Insufficient inventory")
                
                cancelAndIgnoreRemainingEvents()
            }
        }
        
        @Test
        fun `validation error in UseCase propagates correctly`() = runTest {
            // Given
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(testOrders)
            
            // Load profile first
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // When - Try to create order with empty items
            viewModel.events.test {
                viewModel.createQuickOrder(emptyList())  // Invalid: no items
                advanceUntilIdle()
                
                // Then
                val event = awaitItem()
                assertThat(event).isInstanceOf(ProfileViewModel.ProfileEvent.OrderFailed::class.java)
                
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
    
    // ========================================
    // State Management Tests (20%)
    // ========================================
    
    @Nested
    @DisplayName("State Management")
    inner class StateManagementTests {
        
        @Test
        fun `multiple loads replace previous data`() = runTest {
            // Given
            val user1 = testUser.copy(id = "user-1", name = "User One")
            val user2 = testUser.copy(id = "user-2", name = "User Two")
            
            coEvery { userRepository.getUser("user-1") } returns Result.success(user1)
            coEvery { userRepository.getUser("user-2") } returns Result.success(user2)
            coEvery { orderRepository.getOrdersForUser(any()) } returns Result.success(emptyList())
            
            // When - Load user 1, then user 2
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            assertThat(viewModel.state.value.user?.name).isEqualTo("User One")
            
            viewModel.loadProfile("user-2")
            advanceUntilIdle()
            
            // Then - Only user 2 data
            assertThat(viewModel.state.value.user?.name).isEqualTo("User Two")
        }
        
        @Test
        fun `cancelled orders excluded from total calculation`() = runTest {
            // Given
            val ordersWithCancelled = listOf(
                Order("1", "user-1", emptyList(), OrderStatus.DELIVERED, 100.0),
                Order("2", "user-1", emptyList(), OrderStatus.CANCELLED, 50.0),  // Cancelled
                Order("3", "user-1", emptyList(), OrderStatus.SHIPPED, 75.0),
            )
            
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(ordersWithCancelled)
            
            // When
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // Then - Total excludes cancelled order
            val state = viewModel.state.value
            assertThat(state.totalOrderValue).isEqualTo(175.0)  // 100 + 75, not 225
        }
    }
    
    // ========================================
    // Edge Cases (15%)
    // ========================================
    
    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {
        
        @Test
        fun `empty orders list handled correctly`() = runTest {
            // Given
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(emptyList())
            
            // When
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // Then
            val state = viewModel.state.value
            assertThat(state.recentOrders).isEmpty()
            assertThat(state.totalOrderValue).isEqualTo(0.0)
        }
        
        @Test
        fun `more than 5 orders shows only recent 5`() = runTest {
            // Given - 10 orders
            val manyOrders = (1..10).map { i ->
                Order("order-$i", "user-1", emptyList(), OrderStatus.DELIVERED, 10.0 * i)
            }
            
            coEvery { userRepository.getUser("user-1") } returns Result.success(testUser)
            coEvery { orderRepository.getOrdersForUser("user-1") } returns Result.success(manyOrders)
            
            // When
            viewModel.loadProfile("user-1")
            advanceUntilIdle()
            
            // Then - Only 5 recent orders
            val state = viewModel.state.value
            assertThat(state.recentOrders).hasSize(5)
            assertThat(state.recentOrders.map { it.id }).containsExactly(
                "order-1", "order-2", "order-3", "order-4", "order-5"
            )
            
            // But total includes all
            assertThat(state.totalOrderValue).isEqualTo(550.0)  // Sum 1..10 * 10
        }
        
        @Test
        fun `create order without loading profile first does nothing`() = runTest {
            // Given - No profile loaded
            
            // When - Try to create order
            viewModel.createQuickOrder(listOf(OrderItem("prod-1", "Product", 1, 10.0)))
            advanceUntilIdle()
            
            // Then - No repository calls
            coVerify(exactly = 0) { orderRepository.createOrder(any(), any()) }
        }
    }
}

// ============================================================
// MULTI-LAYER INTEGRATION TEST UTILITIES
// ============================================================

/**
 * Helper for setting up common test scenarios.
 */
object IntegrationTestHelpers {
    
    fun createTestUser(
        id: String = "user-${System.currentTimeMillis()}",
        name: String = "Test User",
        email: String = "test@example.com",
    ) = User(id, name, email)
    
    fun createTestOrder(
        id: String = "order-${System.currentTimeMillis()}",
        userId: String = "user-1",
        items: List<OrderItem> = listOf(OrderItem("prod-1", "Product", 1, 10.0)),
        status: OrderStatus = OrderStatus.PENDING,
    ) = Order(
        id = id,
        userId = userId,
        items = items,
        status = status,
        total = items.sumOf { it.price * it.quantity },
    )
    
    fun createTestOrderItem(
        productId: String = "prod-${System.currentTimeMillis()}",
        name: String = "Test Product",
        quantity: Int = 1,
        price: Double = 10.0,
    ) = OrderItem(productId, name, quantity, price)
}

// ============================================================
// PIPELINE INTEGRATION TEST EXAMPLE
// ============================================================

/**
 * Example of testing multi-skill pipeline integration.
 * 
 * For full pipeline testing patterns, see:
 * @see 31-pipeline-patterns.md
 */

/*
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderPipelineIntegrationTest {
    
    @Test
    fun `complete order pipeline executes all skills in sequence`() = runTest {
        // Given
        val validateSkill = mockk<ValidateOrderSkill>()
        val calculateSkill = mockk<CalculatePriceSkill>()
        val processPaymentSkill = mockk<ProcessPaymentSkill>()
        val createOrderSkill = mockk<CreateOrderSkill>()
        
        // Setup pipeline
        val pipeline = pipeline<OrderInput>()
            .pipe(validateSkill)
            .pipe(calculateSkill)
            .pipe(processPaymentSkill)
            .pipe(createOrderSkill)
        
        // Mock skill behaviors
        coEvery { validateSkill.execute(any()) } returns SkillResult.success(ValidatedOrder(...))
        coEvery { calculateSkill.execute(any()) } returns SkillResult.success(PricedOrder(...))
        coEvery { processPaymentSkill.execute(any()) } returns SkillResult.success(PaymentResult(...))
        coEvery { createOrderSkill.execute(any()) } returns SkillResult.success(CreatedOrder(...))
        
        // When
        val result = pipeline.execute(OrderInput(...))
        
        // Then
        assertThat(result.isSuccess).isTrue()
        
        // Verify order
        coVerifyOrder {
            validateSkill.execute(any())
            calculateSkill.execute(any())
            processPaymentSkill.execute(any())
            createOrderSkill.execute(any())
        }
    }
}
*/

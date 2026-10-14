package com.aismp.project.service.impl;

import com.aismp.project.common.ErrorCode;
import com.aismp.project.exception.BusinessException;
import com.aismp.project.mapper.UserMapper;
import com.aismp.aismpcommon.model.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link UserServiceImpl} 纯逻辑单测（不启 Spring 上下文、不连数据库，Mock Mapper）。
 *
 * <p><b>为什么替换掉原来那个 {@code UserServiceTest}</b>：旧版是 {@code @SpringBootTest}
 * 集成测试，需要真实的 MySQL（{@code api_user} 账号）与 RabbitMQ 才能跑，
 * 本机/CI 上必然报 {@code Access denied} / {@code Connection refused}；
 * 且它用 {@code catch (Exception e) {}} 静默吞掉所有异常，等于「永远绿」的假测试。
 * 单元测试的判据必须是<b>纯函数式</b>的：给定输入 → 确定输出，与外部环境无关。
 *
 * <p>本测试锁定 {@code userRegister} / {@code userLogin} 的<b>参数校验口径</b>——
 * 这段是纯逻辑、零 IO，也正是历史 bug 的高发区（改一处阈值就可能放开弱口令注册）：
 * <ul>
 *   <li>任一参数为空 / 账号 &lt; 4 位 / 密码 &lt; 8 位 → PARAMS_ERROR；</li>
 *   <li>两次密码不一致 → PARAMS_ERROR；</li>
 *   <li>账号已存在 → PARAMS_ERROR（「账号重复」）；</li>
 *   <li>校验全过 → 落库，且<b>明文密码绝不入库</b>（必须是 MD5(SALT+password)）。</li>
 * </ul>
 *
 * @author agent-platform
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    /** 全部合法的最小输入，各用例在它基础上只改一个变量，保证「单变量」原则。 */
    private static final String OK_ACCOUNT = "aismp2026";
    private static final String OK_PASSWORD = "Passw0rd!";
    private static final String OK_CHECK = "Passw0rd!";

    /** 捕获 PARAMS_ERROR 并返回消息，便于断言「是哪一条校验拦下的」。 */
    private String registerFail(String account, String pwd, String check) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userRegister(account, pwd, check));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), ex.getCode());
        return ex.getMessage();
    }

    @Test
    @DisplayName("参数为空 → 拦下（三个位置任一为空都拦）")
    void register_blankParams_rejected() {
        assertNotNull(registerFail(null, OK_PASSWORD, OK_CHECK));
        assertNotNull(registerFail("", OK_PASSWORD, OK_CHECK));
        assertNotNull(registerFail(OK_ACCOUNT, null, OK_CHECK));
        assertNotNull(registerFail(OK_ACCOUNT, OK_PASSWORD, ""));
    }

    @Test
    @DisplayName("账号长度 < 4 → 拦下")
    void register_shortAccount_rejected() {
        assertEquals("用户账号过短", registerFail("abc", OK_PASSWORD, OK_CHECK));
    }

    @Test
    @DisplayName("密码或确认密码 < 8 位 → 拦下（弱口令红线）")
    void register_shortPassword_rejected() {
        assertEquals("用户密码过短", registerFail(OK_ACCOUNT, "1234567", "1234567"));
        // 仅「确认密码」过短也要拦——不能只查第一个密码
        assertEquals("用户密码过短", registerFail(OK_ACCOUNT, OK_PASSWORD, "short"));
    }

    @Test
    @DisplayName("两次密码不一致 → 拦下")
    void register_passwordMismatch_rejected() {
        assertEquals("两次输入的密码不一致",
                registerFail(OK_ACCOUNT, OK_PASSWORD, "Passw0rd?"));
    }

    @Test
    @DisplayName("账号已存在 → 拦下（查重走 Mapper，不需要真实库）")
    void register_duplicateAccount_rejected() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertEquals("账号重复", registerFail(OK_ACCOUNT, OK_PASSWORD, OK_CHECK));
    }

    @Test
    @DisplayName("校验全过 → 落库，且明文密码绝不入库（存的是 MD5 摘要）")
    void register_valid_savesHashedPassword() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        // 说明：userRegister 内部走 this.save(user)，那是 MyBatis-Plus ServiceImpl 的
        // 模板方法，它的 baseMapper 字段由 Spring 注入、@InjectMocks 塞不进去，
        // 所以无法直接 verify(userMapper).insert(...)。这里改用 spy 截获 save 的入参
        // ——既能拿到「真正准备落库的那条记录」，又不必拉起 MyBatis 环境。
        UserServiceImpl spy = org.mockito.Mockito.spy(userService);
        // ★ 不要把 captor.capture() 写在 stubbing 的 doAnswer 里：Mockito 的 ArgumentCaptor
        //   只支持配 verify() 使用，在 stub 阶段调用会抛 "No argument value was captured!"。
        //   最朴素的写法就是在 answer 里把对象引用存下来（answer 拿到的是同一个实例）。
        User[] saved = new User[1];
        // 模拟 MyBatis-Plus：save 返回 true 且把自增主键回填到实体上。
        // （真实库靠 JDBC getGeneratedKeys；不回填的话 userRegister 末尾的
        //   user.getId() 会 NPE —— 这是实现层对「自增主键」的硬依赖。）
        org.mockito.Mockito.doAnswer(inv -> {
            User u = inv.getArgument(0);
            saved[0] = u;
            u.setId(123L);
            return true;
        }).when(spy).save(org.mockito.ArgumentMatchers.any(User.class));

        long id = spy.userRegister(OK_ACCOUNT, OK_PASSWORD, OK_CHECK);
        assertEquals(123L, id, "注册应返回落库后的自增主键");

        User arg = saved[0];
        assertNotNull(arg, "应当调用了一次 save");
        assertEquals(OK_ACCOUNT, arg.getUserAccount());
        assertNotEquals(OK_PASSWORD, arg.getUserPassword(), "明文密码绝不能落库");
        // 与实现同口径复算摘要，锁定「盐值 + 算法」不被悄悄换掉
        String expected = org.springframework.util.DigestUtils
                .md5DigestAsHex(("aismp-platform" + OK_PASSWORD).getBytes());
        assertEquals(expected, arg.getUserPassword());
        assertNotNull(arg.getAccessKey(), "注册必须分配 accessKey");
        assertNotNull(arg.getSecretKey(), "注册必须分配 secretKey");
        assertNotEquals(arg.getAccessKey(), arg.getSecretKey(), "accessKey/secretKey 不能相同");
    }

    // ── userLogin 的参数校验（同样零 IO）──

    @Test
    @DisplayName("登录：账号 < 4 位 / 密码 < 8 位 → 拦下")
    void login_shortInput_rejected() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> userService.userLogin("abc", OK_PASSWORD, null));
        assertEquals("账号错误", e1.getMessage());
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> userService.userLogin(OK_ACCOUNT, "1234567", null));
        assertEquals("密码错误", e2.getMessage());
    }

    @Test
    @DisplayName("登录：账号密码为空 → 拦下")
    void login_blankInput_rejected() {
        assertThrows(BusinessException.class, () -> userService.userLogin(null, OK_PASSWORD, null));
        assertThrows(BusinessException.class, () -> userService.userLogin(OK_ACCOUNT, "", null));
    }

    @Test
    @DisplayName("登录：查不到用户 → 拦下（不泄露「账号存在但密码错」）")
    void login_userNotFound_rejected() {
        when(userMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.userLogin(OK_ACCOUNT, OK_PASSWORD, null));
        assertTrue(ex.getMessage().contains("用户不存在或密码错误"));
    }
}

// Package user 承载平台账号（a_user）与登录令牌（a_auth_token）的领域模型。
//
// 表都在 api_db。对应 Java 侧 com.aismp.project.auth.UserPO + AuthRepository。
package user

import "time"

// 角色与状态常量。取值与 a_user.role / a_user.status 列里的字面量一致。
const (
	RoleAdmin       = "ADMIN"
	RoleService     = "SERVICE"
	RoleUser        = "USER"
	RoleAccountMgr  = "ACCOUNT_MANAGER"  // 账管：发起调拨
	RoleWarehouseKp = "WAREHOUSE_KEEPER" // 仓管：出入库确认

	StatusActive   = "ACTIVE"
	StatusDisabled = "DISABLED"
)

// User 是 a_user 的一行。
//
// WarehouseCode / WarehouseName 允许为 NULL（平台级账号没有仓库归属），
// 所以用指针而不是空串——前端靠 null 与 "" 区分「没有仓库」和「仓库名为空」。
type User struct {
	ID            int64
	UserCode      string
	UserName      string
	PasswordHash  string
	Role          string
	Status        string
	WarehouseCode *string
	WarehouseName *string
	CreatedAt     *time.Time
}

// RoleLabel 返回业务角色的中文标签（门户头像旁显示「账管」「仓管」）。
// 平台级角色返回空串——与 Java 侧 UserPO.roleLabel() 一致。
func (u *User) RoleLabel() string {
	switch u.Role {
	case RoleAccountMgr:
		return "账管"
	case RoleWarehouseKp:
		return "仓管"
	default:
		return ""
	}
}

// TokenPrincipal 是令牌解析出的身份，对应 Java 侧 String[]{user_code, role}。
type TokenPrincipal struct {
	UserCode string
	Role     string
}

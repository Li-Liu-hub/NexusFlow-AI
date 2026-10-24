// market 仓储：user / interface_info / user_interface_info 三张表。
//
// 语义对齐 MyBatis-Plus：
//   - 全局逻辑删除：查询自动追加 isDelete = 0，removeById 是 UPDATE isDelete=1
//   - updateById 只更新非空字段（NOT_NULL 策略）
//   - save 只插入非空字段，自增主键回填
//   - QueryWrapper(entity)：非空字段全部等值拼接
package market

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// ErrNotFound 查不到记录（Java 侧 getById 返回 null）。
var ErrNotFound = errors.New("market: 记录不存在")

// User 对应 `user` 表（JSON 形态与 Jackson 序列化 User 实体一致）。
type User struct {
	ID           *int64     `json:"id"`
	UserName     *string    `json:"userName"`
	UserAccount  *string    `json:"userAccount"`
	UserAvatar   *string    `json:"userAvatar"`
	Gender       *int       `json:"gender"`
	UserRole     *string    `json:"userRole"`
	UserPassword *string    `json:"userPassword"`
	AccessKey    *string    `json:"accessKey"`
	SecretKey    *string    `json:"secretKey"`
	CreateTime   *string    `json:"createTime"`
	UpdateTime   *string    `json:"updateTime"`
	IsDelete     *int       `json:"isDelete"`
}

// UserVO 是 /user/get、/user/list 等对外的脱敏视图（不吐 AK/SK/密码）。
type UserVO struct {
	ID          *int64  `json:"id"`
	UserName    *string `json:"userName"`
	UserAccount *string `json:"userAccount"`
	UserAvatar  *string `json:"userAvatar"`
	Gender      *int    `json:"gender"`
	UserRole    *string `json:"userRole"`
	CreateTime  *string `json:"createTime"`
	UpdateTime  *string `json:"updateTime"`
}

// ToVO 对齐 BeanUtils.copyProperties(user, userVO)：同名字段拷贝。
func (u *User) ToVO() *UserVO {
	if u == nil {
		return nil
	}
	return &UserVO{
		ID: u.ID, UserName: u.UserName, UserAccount: u.UserAccount, UserAvatar: u.UserAvatar,
		Gender: u.Gender, UserRole: u.UserRole, CreateTime: u.CreateTime, UpdateTime: u.UpdateTime,
	}
}

// InterfaceInfo 对应 `interface_info` 表。
type InterfaceInfo struct {
	ID             *int64  `json:"id"`
	Name           *string `json:"name"`
	Description    *string `json:"description"`
	URL            *string `json:"url"`
	RequestParams  *string `json:"requestParams"`
	RequestHeader  *string `json:"requestHeader"`
	ResponseHeader *string `json:"responseHeader"`
	Status         *int    `json:"status"`
	Method         *string `json:"method"`
	UserID         *int64  `json:"userId"`
	CreateTime     *string `json:"createTime"`
	UpdateTime     *string `json:"updateTime"`
	IsDelete       *int    `json:"isDelete"`
}

// UserInterfaceInfo 对应 `user_interface_info` 表。
type UserInterfaceInfo struct {
	ID             *int64  `json:"id"`
	UserID         *int64  `json:"userId"`
	InterfaceInfoID *int64 `json:"interfaceInfoId"`
	TotalNum       *int    `json:"totalNum"`
	LeftNum        *int    `json:"leftNum"`
	Status         *int    `json:"status"`
	CreateTime     *string `json:"createTime"`
	UpdateTime     *string `json:"updateTime"`
	IsDelete       *int    `json:"isDelete"`
}

// Repository 是三张表的仓储。
type Repository struct {
	db *sql.DB
}

// NewRepository 构造仓储。
func NewRepository(db *sql.DB) *Repository { return &Repository{db: db} }

// ---------------------------------------------------------------- user

const userColumns = `id, userName, userAccount, userAvatar, gender, userRole,
	userPassword, accessKey, secretKey, createTime, updateTime, isDelete`

func scanUser(row interface{ Scan(...any) error }) (*User, error) {
	var u User
	var createTime, updateTime sql.NullTime
	err := row.Scan(&u.ID, &u.UserName, &u.UserAccount, &u.UserAvatar, &u.Gender, &u.UserRole,
		&u.UserPassword, &u.AccessKey, &u.SecretKey, &createTime, &updateTime, &u.IsDelete)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("market: 读用户失败: %w", err)
	}
	u.CreateTime = isoTime(createTime)
	u.UpdateTime = isoTime(updateTime)
	return &u, nil
}

// UserGetByID 按 id 查用户（自动过滤逻辑删除）。
func (r *Repository) UserGetByID(ctx context.Context, id int64) (*User, error) {
	return scanUser(r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE id = ? AND isDelete = 0`, id))
}

// UserGetByAccount 按账号查用户（注册查重用）。
func (r *Repository) UserGetByAccount(ctx context.Context, account string) (*User, error) {
	return scanUser(r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE userAccount = ? AND isDelete = 0`, account))
}

// UserGetByAK 按接口凭证查用户。
func (r *Repository) UserGetByAK(ctx context.Context, ak string) (*User, error) {
	return scanUser(r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE accessKey = ? AND isDelete = 0`, ak))
}

// UserLoginMatch 按账号 + 密码摘要查用户（登录校验）。
func (r *Repository) UserLoginMatch(ctx context.Context, account, passwordHash string) (*User, error) {
	return scanUser(r.db.QueryRowContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE userAccount = ? AND userPassword = ? AND isDelete = 0`,
		account, passwordHash))
}

// UserInsert 注册/新增用户（只写非空字段），返回自增 id。
func (r *Repository) UserInsert(ctx context.Context, u *User) (int64, error) {
	cols, args := []string{}, []any{}
	appendStr := func(col string, p *string) {
		if p != nil {
			cols = append(cols, col)
			args = append(args, *p)
		}
	}
	appendInt := func(col string, p *int) {
		if p != nil {
			cols = append(cols, col)
			args = append(args, *p)
		}
	}
	appendStr("userName", u.UserName)
	appendStr("userAccount", u.UserAccount)
	appendStr("userAvatar", u.UserAvatar)
	appendInt("gender", u.Gender)
	appendStr("userRole", u.UserRole)
	appendStr("userPassword", u.UserPassword)
	appendStr("accessKey", u.AccessKey)
	appendStr("secretKey", u.SecretKey)
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",")
	res, err := r.db.ExecContext(ctx,
		`INSERT INTO user (`+strings.Join(cols, ", ")+`) VALUES (`+placeholders+`)`, args...)
	if err != nil {
		return 0, fmt.Errorf("market: 新增用户失败: %w", err)
	}
	return res.LastInsertId()
}

// UserUpdateByID 只更新非空字段。
func (r *Repository) UserUpdateByID(ctx context.Context, u *User) (bool, error) {
	sets, args := []string{}, []any{}
	setStr := func(col string, p *string) {
		if p != nil {
			sets = append(sets, col+" = ?")
			args = append(args, *p)
		}
	}
	setInt := func(col string, p *int) {
		if p != nil {
			sets = append(sets, col+" = ?")
			args = append(args, *p)
		}
	}
	setStr("userName", u.UserName)
	setStr("userAccount", u.UserAccount)
	setStr("userAvatar", u.UserAvatar)
	setInt("gender", u.Gender)
	setStr("userRole", u.UserRole)
	setStr("userPassword", u.UserPassword)
	if len(sets) == 0 || u.ID == nil {
		return false, nil
	}
	args = append(args, *u.ID)
	res, err := r.db.ExecContext(ctx,
		`UPDATE user SET `+strings.Join(sets, ", ")+` WHERE id = ? AND isDelete = 0`, args...)
	if err != nil {
		return false, fmt.Errorf("market: 更新用户失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// UserRemoveByID 逻辑删除。
func (r *Repository) UserRemoveByID(ctx context.Context, id int64) (bool, error) {
	res, err := r.db.ExecContext(ctx,
		`UPDATE user SET isDelete = 1 WHERE id = ? AND isDelete = 0`, id)
	if err != nil {
		return false, fmt.Errorf("market: 删除用户失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// UserQuery 是 QueryWrapper(User entity) 的等值条件集：非 nil 字段全部参与 WHERE。
type UserQuery struct {
	ID          *int64
	UserName    *string
	UserAccount *string
	UserAvatar  *string
	Gender      *int
	UserRole    *string
}

// UserList 按等值条件查列表（不排序，与 MP list 一致）。
func (r *Repository) UserList(ctx context.Context, q *UserQuery) ([]*User, error) {
	where, args := q.userWhere()
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE `+where, args...)
	if err != nil {
		return nil, fmt.Errorf("market: 查用户列表失败: %w", err)
	}
	defer rows.Close()
	list := []*User{}
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, u)
	}
	return list, rows.Err()
}

// userWhere 把等值条件集拼成 WHERE 子句（列表 / 分页 / 计数共用）。
func (q *UserQuery) userWhere() (string, []any) {
	where, args := "isDelete = 0", []any{}
	if q == nil {
		return where, args
	}
	if q.ID != nil {
		where += " AND id = ?"
		args = append(args, *q.ID)
	}
	if q.UserName != nil {
		where += " AND userName = ?"
		args = append(args, *q.UserName)
	}
	if q.UserAccount != nil {
		where += " AND userAccount = ?"
		args = append(args, *q.UserAccount)
	}
	if q.UserAvatar != nil {
		where += " AND userAvatar = ?"
		args = append(args, *q.UserAvatar)
	}
	if q.Gender != nil {
		where += " AND gender = ?"
		args = append(args, *q.Gender)
	}
	if q.UserRole != nil {
		where += " AND userRole = ?"
		args = append(args, *q.UserRole)
	}
	return where, args
}

// UserPageCount 分页总数。
func (r *Repository) UserPageCount(ctx context.Context, q *UserQuery) (int64, error) {
	where, args := q.userWhere()
	var total int64
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM user WHERE `+where, args...).Scan(&total); err != nil {
		return 0, fmt.Errorf("market: 用户计数失败: %w", err)
	}
	return total, nil
}

// UserPageRows 分页数据行。
func (r *Repository) UserPageRows(ctx context.Context, q *UserQuery, offset, limit int) ([]*User, error) {
	where, args := q.userWhere()
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+userColumns+` FROM user WHERE `+where+` LIMIT ? OFFSET ?`,
		append(args, limit, offset)...)
	if err != nil {
		return nil, fmt.Errorf("market: 分页查用户失败: %w", err)
	}
	defer rows.Close()
	list := []*User{}
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, u)
	}
	return list, rows.Err()
}

// ---------------------------------------------------------------- interface_info

const interfaceColumns = `id, name, description, url, requestParams, requestHeader,
	responseHeader, status, method, userId, createTime, updateTime, isDelete`

func scanInterface(row interface{ Scan(...any) error }) (*InterfaceInfo, error) {
	var i InterfaceInfo
	var createTime, updateTime sql.NullTime
	err := row.Scan(&i.ID, &i.Name, &i.Description, &i.URL, &i.RequestParams, &i.RequestHeader,
		&i.ResponseHeader, &i.Status, &i.Method, &i.UserID, &createTime, &updateTime, &i.IsDelete)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("market: 读接口失败: %w", err)
	}
	i.CreateTime = isoTime(createTime)
	i.UpdateTime = isoTime(updateTime)
	return &i, nil
}

// InterfaceGetByID 按 id 查接口。
func (r *Repository) InterfaceGetByID(ctx context.Context, id int64) (*InterfaceInfo, error) {
	return scanInterface(r.db.QueryRowContext(ctx,
		`SELECT `+interfaceColumns+` FROM interface_info WHERE id = ? AND isDelete = 0`, id))
}

// InterfaceInsert 新增接口（非空字段），返回自增 id。
func (r *Repository) InterfaceInsert(ctx context.Context, i *InterfaceInfo) (int64, error) {
	cols, args := []string{}, []any{}
	add := func(col string, v any, present bool) {
		if present {
			cols = append(cols, col)
			args = append(args, v)
		}
	}
	add("name", deref(i.Name), i.Name != nil)
	add("description", deref(i.Description), i.Description != nil)
	add("url", deref(i.URL), i.URL != nil)
	add("requestParams", deref(i.RequestParams), i.RequestParams != nil)
	add("requestHeader", deref(i.RequestHeader), i.RequestHeader != nil)
	add("responseHeader", deref(i.ResponseHeader), i.ResponseHeader != nil)
	add("method", deref(i.Method), i.Method != nil)
	if i.UserID != nil {
		add("userId", *i.UserID, true)
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",")
	res, err := r.db.ExecContext(ctx,
		`INSERT INTO interface_info (`+strings.Join(cols, ", ")+`) VALUES (`+placeholders+`)`, args...)
	if err != nil {
		return 0, fmt.Errorf("market: 新增接口失败: %w", err)
	}
	return res.LastInsertId()
}

// InterfaceUpdateByID 只更新非空字段。
func (r *Repository) InterfaceUpdateByID(ctx context.Context, i *InterfaceInfo) (bool, error) {
	sets, args := []string{}, []any{}
	set := func(col string, v any, present bool) {
		if present {
			sets = append(sets, col+" = ?")
			args = append(args, v)
		}
	}
	set("name", deref(i.Name), i.Name != nil)
	set("description", deref(i.Description), i.Description != nil)
	set("url", deref(i.URL), i.URL != nil)
	set("requestHeader", deref(i.RequestHeader), i.RequestHeader != nil)
	set("responseHeader", deref(i.ResponseHeader), i.ResponseHeader != nil)
	set("status", derefInt(i.Status), i.Status != nil)
	set("method", deref(i.Method), i.Method != nil)
	if len(sets) == 0 || i.ID == nil {
		return false, nil
	}
	args = append(args, *i.ID)
	res, err := r.db.ExecContext(ctx,
		`UPDATE interface_info SET `+strings.Join(sets, ", ")+` WHERE id = ? AND isDelete = 0`, args...)
	if err != nil {
		return false, fmt.Errorf("market: 更新接口失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// InterfaceRemoveByID 逻辑删除。
func (r *Repository) InterfaceRemoveByID(ctx context.Context, id int64) (bool, error) {
	res, err := r.db.ExecContext(ctx,
		`UPDATE interface_info SET isDelete = 1 WHERE id = ? AND isDelete = 0`, id)
	if err != nil {
		return false, fmt.Errorf("market: 删除接口失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// InterfaceQuery 是等值条件集（list / list/page 共用）。
// Description 单独存：list/page 会把它从等值条件里摘出去改走 LIKE（Java 侧即如此）。
type InterfaceQuery struct {
	ID             *int64
	Name           *string
	Description    *string
	URL            *string
	RequestParams  *string
	RequestHeader  *string
	ResponseHeader *string
	Status         *int
	Method         *string
	UserID         *int64
}

func (q *InterfaceQuery) where(excludeDescription bool, extra func(set func(col string, v any))) (string, []any) {
	where, args := "isDelete = 0", []any{}
	eqStr := func(col string, p *string) {
		if p != nil {
			where += " AND " + col + " = ?"
			args = append(args, *p)
		}
	}
	eqInt := func(col string, p *int) {
		if p != nil {
			where += " AND " + col + " = ?"
			args = append(args, *p)
		}
	}
	if q != nil {
		if q.ID != nil {
			where += " AND id = ?"
			args = append(args, *q.ID)
		}
		eqStr("name", q.Name)
		if !excludeDescription {
			eqStr("description", q.Description)
		}
		eqStr("url", q.URL)
		eqStr("requestParams", q.RequestParams)
		eqStr("requestHeader", q.RequestHeader)
		eqStr("responseHeader", q.ResponseHeader)
		eqInt("status", q.Status)
		eqStr("method", q.Method)
		if q.UserID != nil {
			where += " AND userId = ?"
			args = append(args, *q.UserID)
		}
	}
	if extra != nil {
		extra(func(col string, v any) {
			where += " AND " + col + " = ?"
			args = append(args, v)
		})
	}
	return where, args
}

// InterfaceList 等值条件查列表。
func (r *Repository) InterfaceList(ctx context.Context, q *InterfaceQuery) ([]*InterfaceInfo, error) {
	where, args := q.where(false, nil)
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+interfaceColumns+` FROM interface_info WHERE `+where, args...)
	if err != nil {
		return nil, fmt.Errorf("market: 查接口列表失败: %w", err)
	}
	defer rows.Close()
	list := []*InterfaceInfo{}
	for rows.Next() {
		i, err := scanInterface(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, i)
	}
	return list, rows.Err()
}

// InterfacePage 分页 + 可选排序；likeDescription 对应 Java 侧对 content 列的 LIKE
// （该列并不存在——Java 版此处会 SQL 报错，Go 版保留同款 SQL 以免行为分叉后无从对照）。
func (r *Repository) InterfacePage(ctx context.Context, q *InterfaceQuery,
	offset, limit int, orderExpr string, likeDescription string) ([]*InterfaceInfo, int64, error) {
	where, args := q.where(true, nil)
	if likeDescription != "" {
		// Java: queryWrapper.like(isNotBlank(content), "content", content) —— 列名原样
		where += " AND content LIKE ?"
		args = append(args, "%"+likeDescription+"%")
	}
	var total int64
	countArgs := append([]any{}, args...)
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM interface_info WHERE `+where, countArgs...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("market: 接口计数失败: %w", err)
	}
	query := `SELECT ` + interfaceColumns + ` FROM interface_info WHERE ` + where
	if orderExpr != "" {
		query += " ORDER BY " + orderExpr
	}
	query += " LIMIT ? OFFSET ?"
	args = append(args, limit, offset)
	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("market: 分页查接口失败: %w", err)
	}
	defer rows.Close()
	list := []*InterfaceInfo{}
	for rows.Next() {
		i, err := scanInterface(rows)
		if err != nil {
			return nil, 0, err
		}
		list = append(list, i)
	}
	return list, total, rows.Err()
}

// ---------------------------------------------------------------- user_interface_info

const quotaColumns = `id, userId, interfaceInfoId, totalNum, leftNum, status, createTime, updateTime, isDelete`

func scanQuotaRow(row interface{ Scan(...any) error }) (*UserInterfaceInfo, error) {
	var q UserInterfaceInfo
	var createTime, updateTime sql.NullTime
	err := row.Scan(&q.ID, &q.UserID, &q.InterfaceInfoID, &q.TotalNum, &q.LeftNum, &q.Status,
		&createTime, &updateTime, &q.IsDelete)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("market: 读额度失败: %w", err)
	}
	q.CreateTime = isoTime(createTime)
	q.UpdateTime = isoTime(updateTime)
	return &q, nil
}

// QuotaGetByID 按 id 查额度行。
func (r *Repository) QuotaGetByID(ctx context.Context, id int64) (*UserInterfaceInfo, error) {
	return scanQuotaRow(r.db.QueryRowContext(ctx,
		`SELECT `+quotaColumns+` FROM user_interface_info WHERE id = ? AND isDelete = 0`, id))
}

// QuotaInsert 新增额度行，返回自增 id。
func (r *Repository) QuotaInsert(ctx context.Context, q *UserInterfaceInfo) (int64, error) {
	cols, args := []string{}, []any{}
	add := func(col string, v any, present bool) {
		if present {
			cols = append(cols, col)
			args = append(args, v)
		}
	}
	add("userId", derefInt64(q.UserID), q.UserID != nil)
	add("interfaceInfoId", derefInt64(q.InterfaceInfoID), q.InterfaceInfoID != nil)
	add("totalNum", derefInt(q.TotalNum), q.TotalNum != nil)
	add("leftNum", derefInt(q.LeftNum), q.LeftNum != nil)
	add("status", derefInt(q.Status), q.Status != nil)
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(cols)), ",")
	res, err := r.db.ExecContext(ctx,
		`INSERT INTO user_interface_info (`+strings.Join(cols, ", ")+`) VALUES (`+placeholders+`)`, args...)
	if err != nil {
		return 0, fmt.Errorf("market: 新增额度失败: %w", err)
	}
	return res.LastInsertId()
}

// QuotaUpdateByID 只更新非空字段。
func (r *Repository) QuotaUpdateByID(ctx context.Context, q *UserInterfaceInfo) (bool, error) {
	sets, args := []string{}, []any{}
	set := func(col string, v any, present bool) {
		if present {
			sets = append(sets, col+" = ?")
			args = append(args, v)
		}
	}
	set("totalNum", derefInt(q.TotalNum), q.TotalNum != nil)
	set("leftNum", derefInt(q.LeftNum), q.LeftNum != nil)
	set("status", derefInt(q.Status), q.Status != nil)
	set("isDelete", derefInt(q.IsDelete), q.IsDelete != nil)
	if len(sets) == 0 || q.ID == nil {
		return false, nil
	}
	args = append(args, *q.ID)
	res, err := r.db.ExecContext(ctx,
		`UPDATE user_interface_info SET `+strings.Join(sets, ", ")+` WHERE id = ? AND isDelete = 0`, args...)
	if err != nil {
		return false, fmt.Errorf("market: 更新额度失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// QuotaRemoveByID 逻辑删除。
func (r *Repository) QuotaRemoveByID(ctx context.Context, id int64) (bool, error) {
	res, err := r.db.ExecContext(ctx,
		`UPDATE user_interface_info SET isDelete = 1 WHERE id = ? AND isDelete = 0`, id)
	if err != nil {
		return false, fmt.Errorf("market: 删除额度失败: %w", err)
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}

// QuotaQuery 等值条件集。
type QuotaQuery struct {
	UserID          *int64
	InterfaceInfoID *int64
	TotalNum        *int
	LeftNum         *int
	Status          *int
}

func (q *QuotaQuery) where() (string, []any) {
	where, args := "isDelete = 0", []any{}
	if q == nil {
		return where, args
	}
	if q.UserID != nil {
		where += " AND userId = ?"
		args = append(args, *q.UserID)
	}
	if q.InterfaceInfoID != nil {
		where += " AND interfaceInfoId = ?"
		args = append(args, *q.InterfaceInfoID)
	}
	if q.TotalNum != nil {
		where += " AND totalNum = ?"
		args = append(args, *q.TotalNum)
	}
	if q.LeftNum != nil {
		where += " AND leftNum = ?"
		args = append(args, *q.LeftNum)
	}
	if q.Status != nil {
		where += " AND status = ?"
		args = append(args, *q.Status)
	}
	return where, args
}

// QuotaList 等值条件查列表。
func (r *Repository) QuotaList(ctx context.Context, q *QuotaQuery) ([]*UserInterfaceInfo, error) {
	where, args := q.where()
	rows, err := r.db.QueryContext(ctx,
		`SELECT `+quotaColumns+` FROM user_interface_info WHERE `+where, args...)
	if err != nil {
		return nil, fmt.Errorf("market: 查额度列表失败: %w", err)
	}
	defer rows.Close()
	list := []*UserInterfaceInfo{}
	for rows.Next() {
		q, err := scanQuotaRow(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, q)
	}
	return list, rows.Err()
}

// QuotaPage 分页 + 可选排序。
func (r *Repository) QuotaPage(ctx context.Context, q *QuotaQuery,
	offset, limit int, orderExpr string) ([]*UserInterfaceInfo, int64, error) {
	where, args := q.where()
	var total int64
	if err := r.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM user_interface_info WHERE `+where, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("market: 额度计数失败: %w", err)
	}
	query := `SELECT ` + quotaColumns + ` FROM user_interface_info WHERE ` + where
	if orderExpr != "" {
		query += " ORDER BY " + orderExpr
	}
	query += " LIMIT ? OFFSET ?"
	args = append(args, limit, offset)
	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("market: 分页查额度失败: %w", err)
	}
	defer rows.Close()
	list := []*UserInterfaceInfo{}
	for rows.Next() {
		q, err := scanQuotaRow(rows)
		if err != nil {
			return nil, 0, err
		}
		list = append(list, q)
	}
	return list, total, rows.Err()
}

// ---------------------------------------------------------------- 杂项

func isoTime(t sql.NullTime) *string {
	if !t.Valid {
		return nil
	}
	s := t.Time.Format("2006-01-02T15:04:05")
	return &s
}

func deref[T any](p *T) T {
	if p == nil {
		var zero T
		return zero
	}
	return *p
}

func derefInt(p *int) int {
	if p == nil {
		return 0
	}
	return *p
}

func derefInt64(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

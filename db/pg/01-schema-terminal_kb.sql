-- ============================================================
--  终端 AI 智慧管理平台 · 向量/编排库 terminal_kb（仅表结构，不含向量数据）
-- ------------------------------------------------------------
--  生成方式：pg_dump --schema-only（PostgreSQL 18 + pgvector）
--  需要扩展：vector（pgvector）、hstore、uuid-ossp
--
--  表说明：
--    vector_store           —— 知识库向量（BAAI/bge-m3，1024 维）
--    orch_conversation      —— 编排层会话（含 graph_version 会话粘版本）
--    orch_message           —— 编排层消息
--    orch_graph             —— 画布编排图（draft / published 版本固化 + 回滚）
--    orch_node_def          —— 画布节点定义（种子见 10-seed-terminal_kb.sql）
--    spring_ai_chat_memory  —— Spring AI 对话记忆
--
--  OA 场景残留表（base_user / tm_area / tm_role / tm_user_role /
--  tm_role_job / tm_work_inst / tm_order_track）属已删除的 OA 域，
--  本文件不包含；线上若仍存在，可执行 99-drop-oa-legacy.sql 清理。
-- ============================================================

--
-- PostgreSQL database dump
--

\restrict uL2aqo8xcCyAwOKpffiifyUtjAbd0beng5IT4UPf5jfjBi6RvAwQ0Jm15KdvmlT

-- Dumped from database version 18.4
-- Dumped by pg_dump version 18.4
--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it
--
-- Name: hstore; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public;
--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;
--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
--
-- Name: orch_conversation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orch_conversation (
    id character varying(40) NOT NULL,
    user_id character varying(64),
    warehouse_code character varying(32),
    title character varying(200),
    message_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
--
-- Name: orch_graph; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orch_graph (
    id bigint NOT NULL,
    version integer NOT NULL,
    status character varying(16) NOT NULL,
    dsl jsonb NOT NULL,
    note character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--
-- Name: orch_graph_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.orch_graph_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--
-- Name: orch_graph_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.orch_graph_id_seq OWNED BY public.orch_graph.id;
--
-- Name: orch_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orch_message (
    id bigint NOT NULL,
    conversation_id character varying(40) NOT NULL,
    role character varying(16) NOT NULL,
    content text,
    route character varying(16),
    cards jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--
-- Name: orch_message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.orch_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
--
-- Name: orch_message_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.orch_message_id_seq OWNED BY public.orch_message.id;
--
-- Name: orch_node_def; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orch_node_def (
    id character varying(96) NOT NULL,
    impl_type character varying(64) NOT NULL,
    label character varying(64) NOT NULL,
    category character varying(32) NOT NULL,
    description character varying(240),
    params_schema jsonb DEFAULT '[]'::jsonb NOT NULL,
    params_default jsonb DEFAULT '{}'::jsonb NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 100 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);
--
-- Name: spring_ai_chat_memory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_ai_chat_memory (
    conversation_id character varying(36) NOT NULL,
    content text NOT NULL,
    type character varying(10) NOT NULL,
    "timestamp" timestamp without time zone NOT NULL,
    CONSTRAINT spring_ai_chat_memory_type_check CHECK (((type)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying, 'TOOL'::character varying])::text[])))
);
--
-- Name: vector_store; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vector_store (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    content text,
    metadata json,
    embedding public.vector(1024)
);
--
-- Name: orch_graph id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_graph ALTER COLUMN id SET DEFAULT nextval('public.orch_graph_id_seq'::regclass);
--
-- Name: orch_message id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_message ALTER COLUMN id SET DEFAULT nextval('public.orch_message_id_seq'::regclass);
--
-- Name: orch_conversation orch_conversation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_conversation
    ADD CONSTRAINT orch_conversation_pkey PRIMARY KEY (id);
--
-- Name: orch_graph orch_graph_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_graph
    ADD CONSTRAINT orch_graph_pkey PRIMARY KEY (id);
--
-- Name: orch_message orch_message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_message
    ADD CONSTRAINT orch_message_pkey PRIMARY KEY (id);
--
-- Name: orch_node_def orch_node_def_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orch_node_def
    ADD CONSTRAINT orch_node_def_pkey PRIMARY KEY (id);
--
-- Name: vector_store vector_store_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vector_store
    ADD CONSTRAINT vector_store_pkey PRIMARY KEY (id);
--
-- Name: idx_orch_conversation_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orch_conversation_user ON public.orch_conversation USING btree (user_id, updated_at DESC);
--
-- Name: idx_orch_graph_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_orch_graph_draft ON public.orch_graph USING btree (version) WHERE ((status)::text = 'draft'::text);
--
-- Name: idx_orch_graph_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_orch_graph_version ON public.orch_graph USING btree (version);
--
-- Name: idx_orch_message_conv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_orch_message_conv ON public.orch_message USING btree (conversation_id, id);
--
-- Name: spring_ai_chat_memory_conversation_id_timestamp_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx ON public.spring_ai_chat_memory USING btree (conversation_id, "timestamp");
--
-- Name: spring_ai_vector_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_ai_vector_index ON public.vector_store USING hnsw (embedding public.vector_cosine_ops);


--
-- PostgreSQL database dump complete
--

\unrestrict uL2aqo8xcCyAwOKpffiifyUtjAbd0beng5IT4UPf5jfjBi6RvAwQ0Jm15KdvmlT

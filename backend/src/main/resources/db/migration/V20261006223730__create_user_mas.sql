CREATE TABLE "TBL_USER_MAS" (
    um_user_code BIGINT
        GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1),
    um_user_name VARCHAR(100) NOT NULL,
    um_oauth_type VARCHAR(20) NOT NULL,
    um_oauth_subject VARCHAR(255) NOT NULL,
    um_reg_date TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_mas
        PRIMARY KEY (um_user_code),

    CONSTRAINT uk_user_mas_oauth
        UNIQUE (um_oauth_type, um_oauth_subject)
);

COMMENT ON TABLE "TBL_USER_MAS" IS '회원 정보';

COMMENT ON COLUMN "TBL_USER_MAS".um_user_code IS '회원 코드';
COMMENT ON COLUMN "TBL_USER_MAS".um_user_name IS '표시 이름';
COMMENT ON COLUMN "TBL_USER_MAS".um_oauth_type IS '로그인 제공자: GOOGLE, KAKAO 등';
COMMENT ON COLUMN "TBL_USER_MAS".um_oauth_subject IS '로그인 제공자의 사용자 고유 식별값';
COMMENT ON COLUMN "TBL_USER_MAS".um_reg_date IS '가입일시';

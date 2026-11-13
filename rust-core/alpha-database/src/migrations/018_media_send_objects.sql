-- T3: доверенная запись о транспортных объектах незавершённой отправки.
--
-- Зачем она нужна. Репликация обязана заново выработать БАЙТ В БАЙТ тот же
-- зашифрованный объект и убедиться, что он совпал с ожидаемым. Для этого ядру
-- нужны три вещи: где лежит неизменный выход, какая идентичность ожидается и
-- какой это кусок из скольких.
--
-- Раньше путь к выходу жил ТОЛЬКО в Kotlin, в файле задания. Принимать его
-- параметром из Kotlin нельзя: это означало бы «прочитай и отправь любой файл,
-- который я назову» — ровно та граница, которую мы закрывали в egress. Поэтому
-- путь становится доверенной записью ядра, а снаружи приходит только
-- идентичность отправки.
CREATE TABLE IF NOT EXISTS media_send_objects (
    identity      TEXT PRIMARY KEY NOT NULL,  -- send_id#item_index#r<revision>#<object_index>
    send_id       TEXT NOT NULL,
    item_index    INTEGER NOT NULL,
    revision      INTEGER NOT NULL,
    object_index  INTEGER NOT NULL,
    object_total  INTEGER NOT NULL,
    output_path   TEXT NOT NULL,
    expected_id   TEXT NOT NULL,              -- ожидаемый TransportObjectId
    created_at    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_send_objects_send ON media_send_objects(send_id);

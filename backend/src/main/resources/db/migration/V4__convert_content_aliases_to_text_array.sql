CREATE FUNCTION content_item_aliases_to_text_array(source JSONB)
RETURNS TEXT[]
LANGUAGE SQL
IMMUTABLE
AS $$
  SELECT COALESCE(array_agg(alias), ARRAY[]::text[])
    FROM jsonb_array_elements_text(source) AS aliases(alias)
$$;

ALTER TABLE content_items
  ALTER COLUMN aliases DROP DEFAULT,
  ALTER COLUMN aliases TYPE TEXT[] USING content_item_aliases_to_text_array(aliases),
  ALTER COLUMN aliases SET DEFAULT ARRAY[]::text[];

DROP FUNCTION content_item_aliases_to_text_array(JSONB);

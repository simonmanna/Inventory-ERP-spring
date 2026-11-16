-- Product images on quotations: when on, the quotation lines table offers an Image column (each user shows or hides
-- it) and the printed / PDF quotation carries a thumbnail per line.
ALTER TABLE "SalesSettings" ADD COLUMN "quoteLineImages" BOOLEAN NOT NULL DEFAULT false;

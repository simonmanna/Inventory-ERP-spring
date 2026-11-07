import { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  ChevronRight, ArrowLeft, Save, Package, Plus, Trash2,
  DollarSign, FlaskConical, Boxes, BookOpen,
  PackageOpen, Layers,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ProductUnitProfileCard } from '@/features/products/unit-profile-card';
import { VariantsTab } from '@/features/products/variants-tab';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import { notify } from '@/lib/notify';
import {
  useCreateProduct, useProductCategories, useProducts, useUpdateProduct,
  type ProductCategory,
} from '@/features/products/api';
import { useUoms } from '@/features/uom/api';
import { useAccounts, type Account } from '@/features/accounting/api';
import { useKitchenStations } from '@/pages/pos/pos-features-api';
import { api, resolveAssetUrl } from '@/lib/api';
import { toast } from 'sonner';
import { tx } from '@/lib/i18n/tx';

/* eslint-disable @typescript-eslint/no-explicit-any */

const PRODUCT_TYPES = ['stockable', 'consumable', 'service', 'fee', 'subscription', 'asset'] as const;

const MEASUREMENT_METHODS = [
  { value: 'count', label: tx('Count units') },
  { value: 'manual_volume', label: tx('Manual remaining volume') },
  { value: 'digital_weight', label: tx('Digital weight scale (bar alcohol)') },
] as const;

const COSTING_METHODS = [
  { value: 'AVCO', label: tx('Average Cost (AVCO)') },
  { value: 'FIFO', label: tx('First-In, First-Out (FIFO)') },
  { value: 'STANDARD', label: tx('Standard Cost') },
  { value: 'SPECIFIC', label: tx('Specific Identification') },
] as const;

const PICKING_STRATEGIES = [
  { value: 'FEFO', label: tx('FEFO — nearest expiry first') },
  { value: 'FIFO', label: tx('FIFO — oldest receipt first') },
  { value: 'MANUAL', label: tx('Manual batch selection') },
  { value: 'SERIAL', label: tx('Serial selection') },
] as const;

const INVENTORY_DEFAULTS = {
  batchTracking: false,
  canBeSold: true,
  stockPolicy: 'warn',
  manufacturingRole: '',
  expiryTracking: false,
  serialTracking: false,
  pickingStrategy: 'FEFO',
} as const;

const UOM_DEFAULTS = {
  uomId: '',
  purchaseUomId: '',
  salesUomId: '',
  recipeUomId: '',
  productionUomId: '',
  uomConversion: '',
  reorderQty: '',
  station: 'cafe',
  allowFractionalSale: true,
  minSaleQty: '',
  maxSaleQty: '',
} as const;

const schema = z.object({
  code: z.string().min(1, tx('Code is required')),
  sku: z.string().optional().or(z.literal('')),
  name: z.string().min(1, tx('Name is required')),
  productType: z.string().min(1),
  costingMethod: z.string(),
  categoryId: z.string().optional().or(z.literal('')),
  salesPrice: z.string().optional().or(z.literal('')),
  costPrice: z.string().optional().or(z.literal('')),
  trackInventory: z.boolean(),
  uomId: z.string().optional().or(z.literal('')),
  purchaseUomId: z.string().optional().or(z.literal('')),
  salesUomId: z.string().optional().or(z.literal('')),
  recipeUomId: z.string().optional().or(z.literal('')),
  productionUomId: z.string().optional().or(z.literal('')),
  uomConversion: z.string().optional().or(z.literal('')),
  reorderQty: z.string().optional().or(z.literal('')),
  station: z.string().optional().or(z.literal('')),
  allowFractionalSale: z.boolean(),
  minSaleQty: z.string().optional().or(z.literal('')),
  maxSaleQty: z.string().optional().or(z.literal('')),
  batchTracking: z.boolean(),
  canBeSold: z.boolean(),
  stockPolicy: z.string(),
  manufacturingRole: z.string(),
  expiryTracking: z.boolean(),
  serialTracking: z.boolean(),
  pickingStrategy: z.string(),
  measurementMethod: z.string(),
  containerVolumeMl: z.string().optional().or(z.literal('')),
  emptyBottleWeightG: z.string().optional().or(z.literal('')),
  actualEmptyWeightG: z.string().optional().or(z.literal('')),
  fullBottleWeightG: z.string().optional().or(z.literal('')),
  standardPourMl: z.string().optional().or(z.literal('')),
  doublePourMl: z.string().optional().or(z.literal('')),
  allowPartialBottle: z.boolean(),
  varianceToleranceG: z.string().optional().or(z.literal('')),
  incomeAccountOverrideId: z.string().optional().or(z.literal('')),
  expenseAccountOverrideId: z.string().optional().or(z.literal('')),
  inventoryAccountOverrideId: z.string().optional().or(z.literal('')),
  cogsAccountOverrideId: z.string().optional().or(z.literal('')),
  shrinkageAccountOverrideId: z.string().optional().or(z.literal('')),
  damageAccountOverrideId: z.string().optional().or(z.literal('')),
  expiryAccountOverrideId: z.string().optional().or(z.literal('')),
  varianceGainAccountOverrideId: z.string().optional().or(z.literal('')),
});
type FormValues = z.infer<typeof schema>;

const BEVERAGE_DEFAULTS = {
  measurementMethod: 'count',
  containerVolumeMl: '',
  emptyBottleWeightG: '',
  actualEmptyWeightG: '',
  fullBottleWeightG: '',
  standardPourMl: '',
  doublePourMl: '',
  allowPartialBottle: true,
  varianceToleranceG: '',
} as const;

// ── Account Select field descriptor ──

type CatAccKey = keyof Omit<ProductCategory, 'id' | 'name' | 'parentId'>;

interface AccDef {
  fieldName: keyof FormValues;
  label: string;
  categoryField: CatAccKey;
  mappingLabel: string;
  mappingKey: string;
}

const ACCOUNT_OVERRIDE_FIELDS: AccDef[] = [
  { fieldName: 'incomeAccountOverrideId', label: tx('Income (Revenue)'),  categoryField: 'incomeAccountId',  mappingKey: 'sales_revenue',           mappingLabel: tx('Sales Revenue') },
  { fieldName: 'expenseAccountOverrideId', label: tx('Expense (COGS)'),   categoryField: 'expenseAccountId', mappingKey: 'cogs',                   mappingLabel: tx('Cost of Goods Sold') },
  { fieldName: 'inventoryAccountOverrideId', label: tx('Inventory (Stock)'), categoryField: 'inventoryAccountId', mappingKey: 'stock_valuation',     mappingLabel: tx('Stock Valuation') },
  { fieldName: 'cogsAccountOverrideId', label: tx('COGS (separate)'),    categoryField: 'cogsAccountId',     mappingKey: 'cogs',                   mappingLabel: tx('Cost of Goods Sold') },
  { fieldName: 'shrinkageAccountOverrideId', label: tx('Adj Loss / Shrinkage'), categoryField: 'shrinkageAccountId', mappingKey: 'stock_adjustment_expense', mappingLabel: tx('Stock Adj Loss') },
  { fieldName: 'varianceGainAccountOverrideId', label: tx('Adj Gain / Variance'), categoryField: 'varianceGainAccountId', mappingKey: 'stock_adjustment_income',  mappingLabel: tx('Stock Adj Gain') },
  { fieldName: 'damageAccountOverrideId', label: tx('Damage Write-Off'), categoryField: 'damageAccountId',   mappingKey: 'stock_adjustment_expense', mappingLabel: tx('Stock Adj Loss') },
  { fieldName: 'expiryAccountOverrideId', label: tx('Expiry Write-Off'), categoryField: 'expiryAccountId',   mappingKey: 'stock_adjustment_expense', mappingLabel: tx('Stock Adj Loss') },
];

// ── Tab definitions matching InventoryDetailPage style ──

interface TabDef {
  id: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

const TABS: TabDef[] = [
  { id: 'general', label: tx('General'), icon: Package },
  { id: 'variants', label: tx('Attributes & Variants'), icon: Layers },
  { id: 'pricing-uom', label: tx('Pricing & UOM'), icon: DollarSign },
  { id: 'inventory', label: tx('Inventory'), icon: PackageOpen },
  { id: 'beverage', label: tx('Beverage'), icon: FlaskConical },
  { id: 'packaging', label: tx('Packaging'), icon: Boxes },
  { id: 'accounting', label: tx('Accounting'), icon: BookOpen },
];

// ---------------------------------------------------------------------------

export function ProductEditPage() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const isNew = !id;
  const [activeTab, setActiveTab] = useState('general');

  const { data: listData } = useProducts({ page: 1, pageSize: 200 });
  const existingProduct = !isNew ? listData?.data?.find((p) => p.id === id) : null;

  const createProduct = useCreateProduct();
  const updateProduct = useUpdateProduct();
  const { data: categories = [] } = useProductCategories();
  const { data: kitchenStations = [] } = useKitchenStations();
  const { data: units = [] } = useUoms();
  const { data: accountsData } = useAccounts();
  const accounts: Account[] = accountsData?.data ?? [];

  const accountMap = useMemo(() => {
    const m = new Map<string, Account>();
    for (const a of accounts) m.set(a.id, a);
    return m;
  }, [accounts]);

  const imageFileIdRef = useRef<string>('');
  const [previewSrc, setPreviewSrc] = useState<string>('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadingImg, setUploadingImg] = useState(false);
  const [saving, setSaving] = useState(false);
  const [packagingRows, setPackagingRows] = useState<{ name: string; quantity: string; barcode: string }[]>([]);
  const updatePack = (i: number, field: 'name' | 'quantity' | 'barcode', val: string) =>
    setPackagingRows((rows) => rows.map((r, j) => (j === i ? { ...r, [field]: val } : r)));
  type UnitRow = {
    uomId: string; factor: string; isPurchase: boolean; isSales: boolean;
    salePrice: string; purchasePrice: string; isActive: boolean;
  };
  const [unitRows, setUnitRows] = useState<UnitRow[]>([]);
  const updateUnit = <K extends keyof UnitRow>(i: number, field: K, val: UnitRow[K]) =>
    setUnitRows((rows) => rows.map((r, j) => (j === i ? { ...r, [field]: val } : r)));

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: '', sku: '', name: '', productType: 'stockable', costingMethod: 'AVCO',
      categoryId: '', salesPrice: '', costPrice: '', trackInventory: true,
      ...UOM_DEFAULTS, ...INVENTORY_DEFAULTS, ...BEVERAGE_DEFAULTS,
    },
  });

  // Selected category
  const watchedCategoryId = form.watch('categoryId');
  const selectedCategory = useMemo(
    () => categories.find((c) => c.id === watchedCategoryId) ?? null,
    [categories, watchedCategoryId],
  );

  // ── Helper: render a single account select ──
  const renderAccountSelect = useCallback((def: AccDef) => {
    const overrideValue = (form.watch(def.fieldName) as string) ?? '';
    const categoryAccountId = selectedCategory?.[def.categoryField] as string | null | undefined;
    const inheritedAccount = categoryAccountId ? accountMap.get(categoryAccountId) ?? null : null;

    let inheritHint: string;
    if (overrideValue && accountMap.get(overrideValue)) {
      const a = accountMap.get(overrideValue)!;
      inheritHint = `Override set to ${a.code} — ${a.name}`;
    } else if (inheritedAccount) {
      inheritHint = `Inheriting from category "${selectedCategory?.name}": ${inheritedAccount.code} — ${inheritedAccount.name}`;
    } else {
      inheritHint = `No override set — falls back to org Account Mapping: ${def.mappingLabel} (${def.mappingKey})`;
    }

    return (
      <div key={def.fieldName} className="space-y-1.5">
        <Label className="text-sm font-medium">{def.label}</Label>
        <Select
          value={overrideValue}
          onValueChange={(v: string) => form.setValue(def.fieldName, v === '__inherit__' ? '' : v)}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder={tx('Inherit (no override)')} />
          </SelectTrigger>
          <SelectContent className="max-h-64">
            <SelectItem value="__inherit__">
              <span className="text-muted-foreground italic">{tx('— Inherit —')}</span>
            </SelectItem>
            {accounts
              .filter((a) => a.isActive !== false)
              .map((a) => (
                <SelectItem key={a.id} value={a.id}>
                  <span className="font-mono">{a.code}</span> — {a.name}
                </SelectItem>
              ))}
          </SelectContent>
        </Select>
        <p className="text-[10px] text-muted-foreground">{inheritHint}</p>
      </div>
    );
  }, [form, accounts, accountMap, selectedCategory]);

  // ── Load existing product data for edit mode ──
  useEffect(() => {
    if (!existingProduct) return;
    const p = existingProduct;
    const toStr = (v: unknown) => (v === null || v === undefined ? '' : String(v));
    form.reset({
      code: p.code,
      sku: p.sku ?? '',
      name: p.name,
      productType: p.productType,
      costingMethod: p.costingMethod ?? 'AVCO',
      categoryId: p.categoryId ?? '',
      salesPrice: p.salesPrice ?? '',
      costPrice: p.costPrice ?? '',
      trackInventory: p.trackInventory,
      uomId: p.uomId ?? '',
      purchaseUomId: p.purchaseUomId ?? '',
      salesUomId: p.salesUomId ?? '',
      recipeUomId: p.recipeUomId ?? '',
      productionUomId: p.productionUomId ?? '',
      uomConversion: toStr(p.uomConversion),
      reorderQty: toStr(p.reorderQty),
      station: p.station ?? 'cafe',
      allowFractionalSale: p.allowFractionalSale ?? true,
      minSaleQty: toStr(p.minSaleQty),
      maxSaleQty: toStr(p.maxSaleQty),
      batchTracking: p.batchTracking ?? false,
      canBeSold: p.canBeSold ?? true,
      stockPolicy: p.stockPolicy ?? 'warn',
      manufacturingRole: p.manufacturingRole ?? '',
      expiryTracking: p.expiryTracking ?? false,
      serialTracking: p.serialTracking ?? false,
      pickingStrategy: p.pickingStrategy ?? 'FEFO',
      measurementMethod: p.measurementMethod ?? 'count',
      containerVolumeMl: toStr(p.containerVolumeMl),
      emptyBottleWeightG: toStr(p.emptyBottleWeightG),
      actualEmptyWeightG: toStr(p.actualEmptyWeightG),
      fullBottleWeightG: toStr(p.fullBottleWeightG),
      standardPourMl: toStr(p.standardPourMl),
      doublePourMl: toStr(p.doublePourMl),
      allowPartialBottle: p.allowPartialBottle ?? true,
      varianceToleranceG: toStr(p.varianceToleranceG),
      incomeAccountOverrideId: p.incomeAccountOverrideId ?? '',
      expenseAccountOverrideId: p.expenseAccountOverrideId ?? '',
      inventoryAccountOverrideId: p.inventoryAccountOverrideId ?? '',
      cogsAccountOverrideId: p.cogsAccountOverrideId ?? '',
      shrinkageAccountOverrideId: p.shrinkageAccountOverrideId ?? '',
      damageAccountOverrideId: p.damageAccountOverrideId ?? '',
      expiryAccountOverrideId: p.expiryAccountOverrideId ?? '',
      varianceGainAccountOverrideId: p.varianceGainAccountOverrideId ?? '',
    });
    setPackagingRows(
      (p.packagings ?? []).map((pk) => ({ name: pk.name, quantity: String(pk.quantity), barcode: pk.barcode ?? '' })),
    );
    setUnitRows((p.units ?? []).map((u) => ({
      uomId: u.uomId,
      factor: String(Number(u.factor)),
      isPurchase: u.isPurchase,
      isSales: u.isSales,
      salePrice: toStr(u.salePrice),
      purchasePrice: toStr(u.purchasePrice),
      isActive: u.isActive,
    })));
    const raw = p.image ?? '';
    const uuid = raw.match(/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i)?.[1] ?? '';
    if (raw.startsWith('http') || raw.startsWith('/api/v1/files/')) {
      setPreviewSrc(resolveAssetUrl(raw) ?? '');
      imageFileIdRef.current = uuid;
    } else if (raw) {
      imageFileIdRef.current = raw;
      api.post(`/files/${encodeURIComponent(raw)}/signed-url`)
        .then((r) => { if (r.data?.url) setPreviewSrc(resolveAssetUrl(r.data.url) ?? ''); })
        .catch(() => setPreviewSrc(''));
    } else {
      setPreviewSrc('');
      imageFileIdRef.current = '';
    }
  }, [existingProduct]);

  const handleImageUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploadingImg(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('ownerType', 'product');
      if (id) fd.append('ownerId', id);
      const { data } = await api.post('/files/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      const fileId = data.id as string | undefined;
      if (fileId) imageFileIdRef.current = fileId;
      const previewUrl = (data as any).downloadUrl ?? (data as any).url ?? '';
      setPreviewSrc(resolveAssetUrl(previewUrl) ?? '');
    } catch (err: any) {
      toast.error(err?.response?.data?.message ?? tx('Image upload failed'));
    } finally {
      setUploadingImg(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const onSubmit = form.handleSubmit(async (values) => {
    setSaving(true);
    try {
      const numOrUndef = (v?: string) => (v !== undefined && v !== '' ? Number(v) : undefined);
      const isWeight = values.measurementMethod === 'digital_weight';
      const payload = {
        ...values,
        sku: values.sku || undefined,
        manufacturingRole: values.manufacturingRole || undefined,
        categoryId: values.categoryId || undefined,
        uomId: values.uomId || undefined,
        purchaseUomId: values.purchaseUomId || undefined,
        salesUomId: values.salesUomId || undefined,
        recipeUomId: values.recipeUomId || undefined,
        productionUomId: values.productionUomId || undefined,
        // Superseded by `units` (ADR-S003); the server keeps it only for older clients.
        uomConversion: undefined,
        units: unitRows
          .filter((r) => r.uomId)
          .map((r) => ({
            uomId: r.uomId,
            factor: Number(r.factor),
            isPurchase: r.isPurchase,
            isSales: r.isSales,
            salePrice: numOrUndef(r.salePrice),
            purchasePrice: numOrUndef(r.purchasePrice),
            isActive: r.isActive,
          })),
        reorderQty: numOrUndef(values.reorderQty),
        station: values.station || undefined,
        allowFractionalSale: values.allowFractionalSale,
        minSaleQty: numOrUndef(values.minSaleQty),
        maxSaleQty: numOrUndef(values.maxSaleQty),
        packagings: packagingRows
          .filter((r) => r.name.trim())
          .map((r) => ({ name: r.name.trim(), quantity: Number(r.quantity) || 0, barcode: r.barcode.trim() || undefined })),
        salesPrice: values.salesPrice ? Number(values.salesPrice) : undefined,
        costPrice: values.costPrice ? Number(values.costPrice) : undefined,
        image: imageFileIdRef.current || (previewSrc || undefined),
        measurementMethod: values.measurementMethod,
        containerVolumeMl: isWeight ? numOrUndef(values.containerVolumeMl) : undefined,
        emptyBottleWeightG: isWeight ? numOrUndef(values.emptyBottleWeightG) : undefined,
        actualEmptyWeightG: isWeight ? numOrUndef(values.actualEmptyWeightG) : undefined,
        fullBottleWeightG: isWeight ? numOrUndef(values.fullBottleWeightG) : undefined,
        standardPourMl: isWeight ? numOrUndef(values.standardPourMl) : undefined,
        doublePourMl: isWeight ? numOrUndef(values.doublePourMl) : undefined,
        allowPartialBottle: isWeight ? values.allowPartialBottle : undefined,
        varianceToleranceG: isWeight ? numOrUndef(values.varianceToleranceG) : undefined,
        incomeAccountOverrideId: values.incomeAccountOverrideId || undefined,
        expenseAccountOverrideId: values.expenseAccountOverrideId || undefined,
        inventoryAccountOverrideId: values.inventoryAccountOverrideId || undefined,
        cogsAccountOverrideId: values.cogsAccountOverrideId || undefined,
        shrinkageAccountOverrideId: values.shrinkageAccountOverrideId || undefined,
        damageAccountOverrideId: values.damageAccountOverrideId || undefined,
        expiryAccountOverrideId: values.expiryAccountOverrideId || undefined,
        varianceGainAccountOverrideId: values.varianceGainAccountOverrideId || undefined,
      };
      if (isNew) {
        await createProduct.mutateAsync(payload);
        notify.success(tx('Product created'));
      } else {
        await updateProduct.mutateAsync({ id, data: payload });
        notify.success(tx('Product updated'));
      }
      navigate('/products');
    } catch (err: any) {
      notify.error(err?.response?.data?.message ?? tx('Save failed'));
    } finally {
      setSaving(false);
    }
  });

  if (!isNew && !existingProduct) {
    return (
      <div className="p-6 space-y-4 min-h-screen">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  const title = isNew ? tx('New Product') : existingProduct?.name ?? tx('Edit Product');
  const subtitle = isNew
    ? tx('Fill in the details to create a new product.')
    : existingProduct?.category?.name ?? tx('Update product details below.');

  return (
    <div className="flex flex-col h-full">
      {/* Breadcrumb + Header — matches InventoryDetailPage styling */}
      <div className="px-6 py-4 border-b bg-white shadow-sm">
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground mb-3">
          <button onClick={() => navigate('/products')} className="hover:text-primary transition-colors font-medium">{tx('Products')}</button>
          <ChevronRight className="h-3 w-3" />
          <span className="font-semibold">{isNew ? tx('New Product') : existingProduct?.name}</span>
        </div>

        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="icon" onClick={() => navigate('/products')} className="h-8 w-8 flex-shrink-0 hover:bg-muted">
              <ArrowLeft className="h-4 w-4" />
            </Button>
            <div className="p-2 bg-primary/10 rounded-lg flex-shrink-0"><Package className="h-5 w-5 text-primary" /></div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-xl font-bold">{title}</h1>
                {!isNew && existingProduct && (
                  <>
                    <code className="text-xs bg-muted px-2 py-0.5 rounded font-mono text-muted-foreground font-semibold">{existingProduct.code}</code>
                    <Badge variant={existingProduct.isActive ? 'default' : 'secondary'} className="text-xs">
                      {existingProduct.isActive ? tx('Active') : tx('Inactive')}
                    </Badge>
                  </>
                )}
              </div>
              <p className="text-sm text-muted-foreground mt-0.5">{subtitle}</p>
            </div>
          </div>

          <div className="flex items-center gap-2 flex-shrink-0">
            <Button variant="outline" size="sm" onClick={() => navigate('/products')}>
              {tx('Cancel')}
            </Button>
            <Button size="sm" onClick={onSubmit} disabled={saving}>
              <Save className="h-4 w-4 mr-1.5" />
              {saving ? tx('Saving…') : tx('Save Product')}
            </Button>
          </div>
        </div>
      </div>

      {/* Tabs + Form — matches InventoryDetailPage styling */}
      <form onSubmit={onSubmit} className="flex-1 overflow-auto flex flex-col">
        {/* Gradient tab bar */}
        <div className="px-2 py-1 bg-gradient-to-r from-primary to-indigo-600 shadow-lg sticky top-0 z-10 mx-2 mt-1 rounded-xl border border-white/10">
          <Tabs value={activeTab} onValueChange={setActiveTab}>
            <TabsList className="bg-transparent p-0 h-auto gap-2 rounded-none w-full justify-start border-none">
              {TABS.map((tab) => (
                <TabsTrigger key={tab.id} value={tab.id}
                  className="relative px-4 py-2 rounded-lg text-sm font-medium text-white/80 hover:bg-white/15 hover:text-white transition-all duration-300 data-[state=active]:bg-white data-[state=active]:text-indigo-600 data-[state=active]:shadow-lg data-[state=active]:font-bold data-[state=active]:scale-105"
                >
                  <span className="flex items-center gap-2">
                    <tab.icon className="h-4 w-4" />
                    <span className="hidden sm:inline">{tab.label}</span>
                  </span>
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        </div>

        {/* Tab content */}
        <div className="flex-1 overflow-auto p-6">
          <Tabs value={activeTab} onValueChange={setActiveTab}>
            {/* ── General Tab ── */}
            <TabsContent value="general" className="mt-0 outline-none space-y-6">
              {/* Basic Info */}
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Basic Information')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-4">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label htmlFor="code" className="text-sm font-medium">{tx('Code *')}</Label>
                      <Input id="code" placeholder={tx('PRD-001')} {...form.register('code')} />
                      {form.formState.errors.code && (
                        <p className="text-sm text-destructive">{form.formState.errors.code.message}</p>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('SKU')}</Label>
                      <Input placeholder={tx('SKU-001')} {...form.register('sku')} />
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-sm font-medium">{tx('Name *')}</Label>
                    <Input placeholder={tx('Product name')} {...form.register('name')} />
                  </div>
                  <div className="grid grid-cols-3 gap-4">
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Product Type')}</Label>
                      <Select value={form.watch('productType')} onValueChange={(v) => form.setValue('productType', v)}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {PRODUCT_TYPES.map((t) => (
                            <SelectItem key={t} value={t}>{t}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Category')}</Label>
                      <Select value={form.watch('categoryId')} onValueChange={(v) => form.setValue('categoryId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Select category')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {categories.map((c) => (
                            <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Costing Method')}</Label>
                      <Select value={form.watch('costingMethod')} onValueChange={(v) => form.setValue('costingMethod', v)}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {COSTING_METHODS.map((c) => (
                            <SelectItem key={c.value} value={c.value}>{c.label}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Kitchen Station')}</Label>
                      <Select value={form.watch('station') || 'cafe'} onValueChange={(v) => form.setValue('station', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Select station')} /></SelectTrigger>
                        <SelectContent>
                          {kitchenStations.map((s) => (
                            <SelectItem key={s.id} value={s.code}>{s.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <p className="text-[11px] text-muted-foreground">{tx('Which KDS screen this product\'s tickets route to.')}</p>
                    </div>
                  </div>
                  {/* Image upload */}
                  <div className="space-y-1.5">
                    <Label className="text-sm font-medium">{tx('Image')}</Label>
                    <div className="flex items-center gap-3">
                      <Button type="button" variant="outline" size="sm" onClick={() => fileInputRef.current?.click()}>
                        {uploadingImg ? tx('Uploading…') : tx('Choose Image')}
                      </Button>
                      <input ref={fileInputRef} type="file" accept="image/*" className="hidden" onChange={handleImageUpload} />
                      {previewSrc && (
                        <img src={previewSrc} alt={tx('Preview')} className="h-12 w-12 object-cover rounded border" />
                      )}
                    </div>
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* ── Attributes & Variants Tab (ADR-S058) ── */}
            <TabsContent value="variants" className="mt-0 outline-none space-y-6">
              <VariantsTab productId={id} />
            </TabsContent>

            {/* ── Pricing & UOM Tab ── */}
            <TabsContent value="pricing-uom" className="mt-0 outline-none space-y-6">
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Pricing')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Sales Price')}</Label>
                      <Input type="number" step="0.01" min={0} placeholder="0.00" {...form.register('salesPrice')} />
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Cost Price')}</Label>
                      <Input type="number" step="0.01" min={0} placeholder="0.00" {...form.register('costPrice')} />
                    </div>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Units of Measure')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-4">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Stock Unit')}</Label>
                      <Select value={form.watch('uomId')} onValueChange={(v) => form.setValue('uomId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Select UOM')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {units.map((u: any) => (
                            <SelectItem key={u.id} value={u.id}>{u.code} — {u.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Purchase Unit')}</Label>
                      <Select value={form.watch('purchaseUomId')} onValueChange={(v) => form.setValue('purchaseUomId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Optional')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {units.map((u: any) => (
                            <SelectItem key={u.id} value={u.id}>{u.code} — {u.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Sales Unit')}</Label>
                      <Select value={form.watch('salesUomId')} onValueChange={(v) => form.setValue('salesUomId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Optional')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {units.map((u: any) => (
                            <SelectItem key={u.id} value={u.id}>{u.code} — {u.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Recipe Unit')}</Label>
                      <Select value={form.watch('recipeUomId')} onValueChange={(v) => form.setValue('recipeUomId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Optional')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {units.map((u: any) => (
                            <SelectItem key={u.id} value={u.id}>{u.code} — {u.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Production Unit')}</Label>
                      <Select value={form.watch('productionUomId')} onValueChange={(v) => form.setValue('productionUomId', v)}>
                        <SelectTrigger><SelectValue placeholder={tx('Optional')} /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="">{tx('None')}</SelectItem>
                          {units.map((u: any) => (
                            <SelectItem key={u.id} value={u.id}>{u.code} — {u.name}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                  <p className="text-[11px] text-muted-foreground">
                    {tx('Stock is always counted in the stock unit. It cannot be changed once stock has moved.')}
                  </p>
                  <div className="grid grid-cols-3 gap-4">
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Reorder Qty')}</Label>
                      <Input type="number" min={0} placeholder="0" {...form.register('reorderQty')} />
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Min Sale Qty')}</Label>
                      <Input type="number" min={0} step="0.01" placeholder="0" {...form.register('minSaleQty')} />
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Max Sale Qty')}</Label>
                      <Input type="number" min={0} step="0.01" placeholder="0" {...form.register('maxSaleQty')} />
                    </div>
                  </div>
                </CardContent>
              </Card>

              {!isNew && id && <ProductUnitProfileCard productId={id} />}

              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg flex-row items-center justify-between">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Pack Sizes & Extra Units')}</CardTitle>
                  <Button
                    type="button" size="sm" variant="outline"
                    disabled={!form.watch('uomId')}
                    onClick={() => setUnitRows([...unitRows, {
                      uomId: '', factor: '', isPurchase: true, isSales: true, salePrice: '', purchasePrice: '', isActive: true,
                    }])}
                  >
                    <Plus className="h-3.5 w-3.5 mr-1" /> {tx('Add unit')}
                  </Button>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-2">
                  <p className="text-[11px] text-muted-foreground">
                    {tx('Units this product is bought or sold in, with how many stock units each holds (e.g. 1 Box = 24 pieces). Sizes are per product. Changing a size later does not change documents already created.')}
                  </p>
                  {!form.watch('uomId') && (
                    <p className="text-sm text-muted-foreground">{tx('Pick a stock unit first.')}</p>
                  )}
                  {form.watch('uomId') && unitRows.length === 0 && (
                    <p className="text-sm text-muted-foreground">{tx('No extra units. Everything is bought and sold in the stock unit.')}</p>
                  )}
                  {unitRows.length > 0 && (
                    <div className="hidden md:grid grid-cols-[1fr_110px_60px_60px_110px_110px_auto] gap-2 text-[11px] font-medium text-muted-foreground">
                      <span>{tx('Unit')}</span>
                      <span>{tx('Stock units in 1')}</span>
                      <span>{tx('Buy')}</span>
                      <span>{tx('Sell')}</span>
                      <span>{tx('Sale price')}</span>
                      <span>{tx('Purchase price')}</span>
                      <span />
                    </div>
                  )}
                  {unitRows.map((row, i) => {
                    const baseCode = units.find((u: any) => u.id === form.watch('uomId'))?.code ?? '';
                    const salesPrice = Number(form.watch('salesPrice') || 0);
                    const factor = Number(row.factor || 0);
                    return (
                      <div key={i} className="grid grid-cols-2 md:grid-cols-[1fr_110px_60px_60px_110px_110px_auto] gap-2 items-center">
                        <select
                          aria-label={tx('Unit')}
                          className="w-full rounded-md border bg-background px-2 py-2 text-sm"
                          value={row.uomId}
                          onChange={(e) => updateUnit(i, 'uomId', e.target.value)}
                        >
                          <option value="">{tx('Select unit')}</option>
                          {units
                            .filter((u: any) => u.id !== form.watch('uomId'))
                            .map((u: any) => (
                              <option key={u.id} value={u.id}>{u.code} — {u.name}</option>
                            ))}
                        </select>
                        <div className="flex items-center gap-1">
                          <Input
                            type="number" step="any" min={0} placeholder="24"
                            aria-label={tx('Stock units in 1')}
                            value={row.factor}
                            onChange={(e) => updateUnit(i, 'factor', e.target.value)}
                          />
                          <span className="text-xs text-muted-foreground">{baseCode}</span>
                        </div>
                        <label className="flex items-center gap-1 text-xs">
                          <input type="checkbox" checked={row.isPurchase} onChange={(e) => updateUnit(i, 'isPurchase', e.target.checked)} />
                          <span className="md:hidden">{tx('Buy')}</span>
                        </label>
                        <label className="flex items-center gap-1 text-xs">
                          <input type="checkbox" checked={row.isSales} onChange={(e) => updateUnit(i, 'isSales', e.target.checked)} />
                          <span className="md:hidden">{tx('Sell')}</span>
                        </label>
                        <Input
                          type="number" step="0.01" min={0}
                          aria-label={tx('Sale price')}
                          placeholder={salesPrice && factor ? String(Number((salesPrice * factor).toFixed(2))) : tx('Auto')}
                          value={row.salePrice}
                          onChange={(e) => updateUnit(i, 'salePrice', e.target.value)}
                        />
                        <Input
                          type="number" step="0.01" min={0}
                          aria-label={tx('Purchase price')}
                          placeholder={tx('Auto')}
                          value={row.purchasePrice}
                          onChange={(e) => updateUnit(i, 'purchasePrice', e.target.value)}
                        />
                        <Button type="button" size="icon" variant="ghost" aria-label={tx('Remove unit')} onClick={() => setUnitRows(unitRows.filter((_, j) => j !== i))}>
                          <Trash2 className="h-4 w-4 text-destructive/70" />
                        </Button>
                      </div>
                    );
                  })}
                </CardContent>
              </Card>
            </TabsContent>

            {/* ── Inventory Tab ── */}
            <TabsContent value="inventory" className="mt-0 outline-none space-y-6">
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Inventory Tracking')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-4">
                  <label className="flex items-center gap-2 text-sm cursor-pointer">
                    <input type="checkbox" className="rounded" checked={form.watch('trackInventory')} onChange={(e) => form.setValue('trackInventory', e.target.checked)} />
                    {tx('Track Inventory')}
                  </label>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label className="text-sm font-medium">{tx('Picking Strategy')}</Label>
                      <Select value={form.watch('pickingStrategy')} onValueChange={(v) => form.setValue('pickingStrategy', v)} disabled={!form.watch('trackInventory')}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {PICKING_STRATEGIES.map((s) => (
                            <SelectItem key={s.value} value={s.value}>{s.label}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label htmlFor="product-stage" className="text-sm font-medium">{tx('Production stage')}</Label>
                      <select id="product-stage" className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.watch('manufacturingRole')} onChange={(e) => form.setValue('manufacturingRole', e.target.value)}>
                        <option value="">{tx('Not set')}</option>
                        <option value="raw">{tx('Raw material')}</option>
                        <option value="semi_finished">{tx('Semi-finished')}</option>
                        <option value="finished">{tx('Finished')}</option>
                      </select>
                    </div>
                    <div className="space-y-1.5">
                      <Label htmlFor="product-stock-policy" className="text-sm font-medium">{tx('When stock runs out')}</Label>
                      <select id="product-stock-policy" className="h-9 w-full rounded-md border bg-background px-2 text-sm" value={form.watch('stockPolicy')} onChange={(e) => form.setValue('stockPolicy', e.target.value)}>
                        <option value="block">{tx('Block the issue')}</option>
                        <option value="warn">{tx('Warn and allow')}</option>
                        <option value="silent">{tx('Allow silently')}</option>
                      </select>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-6">
                    <label className="flex items-center gap-2 text-sm cursor-pointer" title={tx('Off takes the product off quotations, orders, invoices and deliveries. Stock is untouched.')}>
                      <input type="checkbox" className="rounded" checked={form.watch('canBeSold')} onChange={(e) => form.setValue('canBeSold', e.target.checked)} />
                      {tx('Can be sold')}
                    </label>
                    <label className="flex items-center gap-2 text-sm cursor-pointer">
                      <input type="checkbox" className="rounded" checked={form.watch('batchTracking')} onChange={(e) => form.setValue('batchTracking', e.target.checked)} disabled={!form.watch('trackInventory')} />
                      {tx('Batch Tracking')}
                    </label>
                    <label className="flex items-center gap-2 text-sm cursor-pointer">
                      <input type="checkbox" className="rounded" checked={form.watch('expiryTracking')} onChange={(e) => form.setValue('expiryTracking', e.target.checked)} disabled={!form.watch('trackInventory')} />
                      {tx('Expiry Tracking')}
                    </label>
                    <label className="flex items-center gap-2 text-sm cursor-pointer">
                      <input type="checkbox" className="rounded" checked={form.watch('serialTracking')} onChange={(e) => form.setValue('serialTracking', e.target.checked)} disabled={!form.watch('trackInventory')} />
                      {tx('Serial Tracking')}
                    </label>
                  </div>
                  {form.watch('expiryTracking') && (
                    <p className="text-[11px] text-slate-500">{tx('Expiry tracking enables Batch tracking (expiry is recorded per batch).')}</p>
                  )}
                  {form.watch('costingMethod') === 'SPECIFIC' && !form.watch('serialTracking') && !form.watch('batchTracking') && (
                    <p className="text-[11px] text-amber-600">{tx('Specific Identification needs Serial or Batch tracking to identify each unit\'s cost.')}</p>
                  )}
                </CardContent>
              </Card>
            </TabsContent>

            {/* ── Beverage Tab ── */}
            <TabsContent value="beverage" className="mt-0 outline-none space-y-6">
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Beverage Control')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-4">
                  <div className="space-y-1.5">
                    <Label className="text-sm font-medium">{tx('Measurement Method')}</Label>
                    <Select value={form.watch('measurementMethod')} onValueChange={(v) => form.setValue('measurementMethod', v)}>
                      <SelectTrigger className="w-72"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        {MEASUREMENT_METHODS.map((m) => (
                          <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  {form.watch('measurementMethod') === 'digital_weight' && (
                    <div className="grid grid-cols-2 gap-4 border rounded-lg bg-slate-50 p-4">
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Container Volume (ml)')}</Label>
                        <Input type="number" min={0} step="1" placeholder="e.g. 750" {...form.register('containerVolumeMl')} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Empty Bottle Weight (g)')}</Label>
                        <Input type="number" min={0} step="0.1" placeholder="e.g. 400" {...form.register('emptyBottleWeightG')} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Full Bottle Weight (g)')}</Label>
                        <Input type="number" min={0} step="0.1" placeholder="e.g. 1150" {...form.register('fullBottleWeightG')} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Single Shot (ml)')}</Label>
                        <Input type="number" min={0} step="0.1" placeholder="e.g. 30" {...form.register('standardPourMl')} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Double Shot (ml)')}</Label>
                        <Input type="number" min={0} step="0.1" placeholder={tx('Default: 2 × single')} {...form.register('doublePourMl')} />
                      </div>
                      <div className="space-y-1.5">
                        <Label className="text-sm">{tx('Variance Tolerance (g)')}</Label>
                        <Input type="number" min={0} step="0.1" placeholder="e.g. 5" {...form.register('varianceToleranceG')} />
                      </div>
                      <div className="flex items-end">
                        <label className="flex items-center gap-2 text-sm cursor-pointer">
                          <input type="checkbox" className="rounded" checked={form.watch('allowPartialBottle')} onChange={(e) => form.setValue('allowPartialBottle', e.target.checked)} />
                          {tx('Allow Partial Bottle')}
                        </label>
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>
            </TabsContent>

            {/* ── Packaging Tab ── */}
            <TabsContent value="packaging" className="mt-0 outline-none space-y-6">
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg flex-row items-center justify-between">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Packaging & Barcodes')}</CardTitle>
                  <Button type="button" size="sm" variant="outline" onClick={() => setPackagingRows([...packagingRows, { name: '', quantity: '', barcode: '' }])}>
                    <Plus className="h-3.5 w-3.5 mr-1" /> {tx('Add pack')}
                  </Button>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-2">
                  <p className="text-[11px] text-muted-foreground">{tx('Each pack = a quantity of the stock unit. Scanning a pack barcode adds that many base units (e.g. a case of 24).')}</p>
                  {packagingRows.length === 0 && <p className="text-sm text-muted-foreground">{tx('No packaging defined.')}</p>}
                  {packagingRows.map((row, i) => (
                    <div key={i} className="grid grid-cols-[1fr_130px_1fr_auto] gap-2 items-center">
                      <Input placeholder={tx('Pack name (e.g. Carton)')} value={row.name} onChange={(e) => updatePack(i, 'name', e.target.value)} />
                      <Input type="number" step="any" min={0} placeholder={tx('qty of base')} value={row.quantity} onChange={(e) => updatePack(i, 'quantity', e.target.value)} />
                      <Input placeholder={tx('Barcode (optional)')} value={row.barcode} onChange={(e) => updatePack(i, 'barcode', e.target.value)} />
                      <Button type="button" size="icon" variant="ghost" onClick={() => setPackagingRows(packagingRows.filter((_, j) => j !== i))}>
                        <Trash2 className="h-4 w-4 text-destructive/70" />
                      </Button>
                    </div>
                  ))}
                </CardContent>
              </Card>
            </TabsContent>

            {/* ── Accounting Tab ── */}
            <TabsContent value="accounting" className="mt-0 outline-none space-y-6">
              <Card>
                <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
                  <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">{tx('Inventory Accounting Overrides')}</CardTitle>
                </CardHeader>
                <CardContent className="px-5 py-4 space-y-1">
                  <p className="text-[11px] text-muted-foreground mb-3">
                    {tx('Leave each field as "')}<span className="italic">{tx('Inherit')}</span>{tx('" to use the category\'s default (when a category is selected) or the org-level Account Mapping.')}
                  </p>
                  <div className="grid grid-cols-2 gap-x-6 gap-y-4">
                    {ACCOUNT_OVERRIDE_FIELDS.map(renderAccountSelect)}
                  </div>
                </CardContent>
              </Card>
            </TabsContent>
          </Tabs>

          {/* Bottom actions — always visible */}
          <div className="flex items-center justify-between pt-6 pb-8 border-t mt-6">
            <Button type="button" variant="outline" onClick={() => navigate('/products')}>{tx('Cancel')}</Button>
            <Button type="submit" disabled={saving}>
              <Save className="h-4 w-4 mr-1.5" />
              {saving ? tx('Saving…') : tx('Save Product')}
            </Button>
          </div>
        </div>
      </form>
    </div>
  );
}

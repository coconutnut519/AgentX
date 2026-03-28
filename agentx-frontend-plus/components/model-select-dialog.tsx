"use client"

import { useEffect, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { getAgentModel, getModels, setAgentModelWithToast } from "@/lib/api-services"
import { CheckCircle, Loader2, Settings } from "lucide-react"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Slider } from "@/components/ui/slider"

interface Model {
  id: string
  userId: string
  providerId: string
  providerName: string | null
  modelId: string
  name: string
  description: string
  type: string
  config: any
  isOfficial: boolean
  status: boolean
  createdAt: string
  updatedAt: string
}

interface ModelConfig {
  modelId: string
  temperature: number
  topP: number
  topK: number
  maxTokens: number
  strategyType: string
  reserveRatio: number
  summaryThreshold: number
  recallTriggerThreshold?: number
  recallTopK?: number
  recallMinScore?: number
  recallMaxCandidates?: number
  enableRerank?: boolean
}

interface ModelSelectDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  agentId: string
  agentName?: string
  currentModelId?: string
  onSuccess?: () => void
}

const strategyOptions = [
  {
    value: "NONE",
    title: "不处理",
    description: "不主动裁剪历史，上下文管理完全依赖模型或上游调用方。",
  },
  {
    value: "SLIDING_WINDOW",
    title: "滑动窗口",
    description: "优先保留最近消息，按预算移除更早历史。",
  },
  {
    value: "SUMMARIZE",
    title: "摘要压缩",
    description: "超出阈值后，把较早历史压缩为摘要，再保留最近原始消息。",
  },
  {
    value: "RELEVANCE_RECALL",
    title: "相关性召回",
    description: "固定保留最近消息，并从更早历史中召回与当前问题最相关的内容。",
  },
]

export function ModelSelectDialog({
  open,
  onOpenChange,
  agentId,
  agentName,
  currentModelId,
  onSuccess,
}: ModelSelectDialogProps) {
  const [models, setModels] = useState<Model[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedModelId, setSelectedModelId] = useState<string | null>(currentModelId || null)
  const [saving, setSaving] = useState(false)

  const [temperature, setTemperature] = useState(0.7)
  const [topP, setTopP] = useState(0.9)
  const [topK, setTopK] = useState(50)
  const [maxTokens, setMaxTokens] = useState(4096)

  const [strategyType, setStrategyType] = useState("NONE")
  const [reserveRatio, setReserveRatio] = useState(0.2)
  const [summaryThreshold, setSummaryThreshold] = useState(80)
  const [recallTriggerThreshold, setRecallTriggerThreshold] = useState(80)
  const [recallTopK, setRecallTopK] = useState(4)
  const [recallMinScore, setRecallMinScore] = useState(0.15)
  const [recallMaxCandidates, setRecallMaxCandidates] = useState(20)
  const [enableRerank, setEnableRerank] = useState(false)

  useEffect(() => {
    async function loadData() {
      setLoading(true)
      try {
        const [modelsResponse, currentModelResponse] = await Promise.all([
          getModels("CHAT"),
          getAgentModel(agentId),
        ])

        if (modelsResponse.code === 200 && Array.isArray(modelsResponse.data)) {
          setModels(modelsResponse.data)
        }

        if (currentModelResponse.code === 200 && currentModelResponse.data) {
          const {
            modelId,
            temperature: temp,
            topP: top,
            topK: k,
            maxTokens: max,
            strategyType: strategy,
            reserveRatio: ratio,
            summaryThreshold: threshold,
            recallTriggerThreshold: recallThreshold,
            recallTopK: topKForRecall,
            recallMinScore: minScore,
            recallMaxCandidates: maxCandidates,
            enableRerank: rerankEnabled,
          } = currentModelResponse.data

          if (modelId) setSelectedModelId(modelId)
          if (temp !== undefined) setTemperature(temp)
          if (top !== undefined) setTopP(top)
          if (k !== undefined) setTopK(k)
          if (max !== undefined) setMaxTokens(max)
          if (strategy !== undefined) setStrategyType(strategy)
          if (ratio !== undefined) setReserveRatio(ratio)
          if (threshold !== undefined) setSummaryThreshold(threshold)
          if (recallThreshold !== undefined) setRecallTriggerThreshold(recallThreshold)
          if (topKForRecall !== undefined) setRecallTopK(topKForRecall)
          if (minScore !== undefined) setRecallMinScore(minScore)
          if (maxCandidates !== undefined) setRecallMaxCandidates(maxCandidates)
          if (rerankEnabled !== undefined) setEnableRerank(rerankEnabled)
        }
      } finally {
        setLoading(false)
      }
    }

    if (open) {
      loadData()
    }
  }, [open, agentId])

  const handleSave = async () => {
    if (!selectedModelId || !agentId) return

    setSaving(true)
    try {
      const modelConfig: ModelConfig = {
        modelId: selectedModelId,
        temperature,
        topP,
        topK,
        maxTokens,
        strategyType,
        reserveRatio,
        summaryThreshold,
        recallTriggerThreshold,
        recallTopK,
        recallMinScore,
        recallMaxCandidates,
        enableRerank,
      }

      const response = await setAgentModelWithToast(agentId, modelConfig)
      if (response.code === 200) {
        onOpenChange(false)
        onSuccess?.()
      }
    } finally {
      setSaving(false)
    }
  }

  const modelsByProvider = models.reduce((groups, model) => {
    const provider = model.providerName || "未知提供商"
    if (!groups[provider]) {
      groups[provider] = []
    }
    groups[provider].push(model)
    return groups
  }, {} as Record<string, Model[]>)

  const renderStrategyCard = (value: string, title: string, description: string) => (
    <button
      key={value}
      type="button"
      className={`rounded-lg border p-4 text-left transition-colors ${
        strategyType === value ? "border-primary bg-primary/5 ring-1 ring-primary" : "border-border hover:border-primary/50"
      }`}
      onClick={() => setStrategyType(value)}
    >
      <div className="mb-2 flex items-center gap-2">
        <div
          className={`h-4 w-4 rounded-full ${
            strategyType === value ? "bg-primary" : "border border-muted-foreground"
          }`}
        >
          {strategyType === value && <div className="m-1 h-2 w-2 rounded-full bg-white" />}
        </div>
        <span className="font-medium">{title}</span>
      </div>
      <p className="text-sm text-muted-foreground">{description}</p>
    </button>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[85vh] max-w-5xl flex-col overflow-hidden">
        <DialogHeader>
          <div className="flex items-center">
            <Settings className="mr-2 h-6 w-6 text-primary" />
            <div>
              <DialogTitle className="text-xl">配置对话模型</DialogTitle>
              <DialogDescription className="mt-1">
                {agentName ? `为 “${agentName}” 选择聊天模型并调整上下文策略。` : "选择聊天模型并调整上下文策略。"}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <Tabs defaultValue="modelSelect" className="mt-4 w-full">
          <TabsList>
            <TabsTrigger value="modelSelect">模型选择</TabsTrigger>
            <TabsTrigger value="modelParams">模型参数</TabsTrigger>
            <TabsTrigger value="tokenStrategy">上下文策略</TabsTrigger>
          </TabsList>

          <TabsContent value="modelSelect" className="pt-4">
            {loading ? (
              <div className="flex justify-center py-10">
                <Loader2 className="h-6 w-6 animate-spin" />
              </div>
            ) : models.length === 0 ? (
              <div className="py-10 text-center text-muted-foreground">当前没有可用的聊天模型，请先在模型管理中配置。</div>
            ) : (
              <ScrollArea className="flex-1 overflow-auto pr-4" style={{ maxHeight: "60vh" }}>
                <RadioGroup value={selectedModelId || ""} onValueChange={setSelectedModelId} className="space-y-6">
                  {Object.entries(modelsByProvider).map(([provider, providerModels]) => (
                    <div key={provider} className="space-y-3">
                      <h3 className="text-sm font-medium uppercase tracking-wider text-muted-foreground">{provider}</h3>
                      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                        {providerModels.map((model) => (
                          <div
                            key={model.id}
                            className={`relative flex h-full cursor-pointer flex-col rounded-lg border p-4 transition-colors ${
                              selectedModelId === model.id
                                ? "border-primary bg-primary/5 ring-1 ring-primary"
                                : "border-border hover:border-primary/50"
                            } ${!model.status ? "opacity-60" : ""}`}
                          >
                            {model.isOfficial && (
                              <div className="absolute -top-2 right-2 z-10">
                                <Badge className="bg-blue-100 px-2 py-0.5 text-blue-700 hover:bg-blue-100">官方</Badge>
                              </div>
                            )}

                            <RadioGroupItem value={model.id} id={model.id} className="sr-only" disabled={!model.status} />

                            <label htmlFor={model.id} className="flex h-full cursor-pointer flex-col">
                              <div className="mb-2 flex items-start justify-between">
                                <span className="text-base font-medium">{model.name || model.modelId}</span>
                                {selectedModelId === model.id && (
                                  <svg
                                    xmlns="http://www.w3.org/2000/svg"
                                    width="20"
                                    height="20"
                                    viewBox="0 0 24 24"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeWidth="2"
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    className="text-blue-600"
                                  >
                                    <circle cx="12" cy="12" r="10" fill="#4285F4" stroke="none" />
                                    <path d="M8 12l2 2 6-6" stroke="white" strokeWidth="2" />
                                  </svg>
                                )}
                              </div>

                              <div className="mb-2 flex-1 text-sm text-muted-foreground">
                                {model.description || "暂无模型描述。"}
                              </div>

                              <div className="mt-auto text-xs text-muted-foreground">模型 ID: {model.modelId}</div>
                            </label>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </RadioGroup>
              </ScrollArea>
            )}
          </TabsContent>

          <TabsContent value="modelParams" className="space-y-6 py-4">
            <div className="space-y-4">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">Temperature</span>
                  <span>{temperature}</span>
                </div>
                <Slider value={[temperature]} min={0} max={2} step={0.1} onValueChange={(value) => setTemperature(value[0])} />
                <p className="text-sm text-muted-foreground">值越高，回答越发散；值越低，回答越稳定。</p>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">Top P</span>
                  <span>{topP}</span>
                </div>
                <Slider value={[topP]} min={0} max={1} step={0.01} onValueChange={(value) => setTopP(value[0])} />
                <p className="text-sm text-muted-foreground">控制候选词的累计概率范围，通常和 Temperature 配合使用。</p>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">Top K</span>
                  <span>{topK}</span>
                </div>
                <Slider value={[topK]} min={1} max={100} step={1} onValueChange={(value) => setTopK(value[0])} />
                <p className="text-sm text-muted-foreground">限制每一步采样时参与候选的 token 数量。</p>
              </div>
            </div>
          </TabsContent>

          <TabsContent value="tokenStrategy" className="space-y-6 py-4">
            <div>
              <h3 className="mb-4 font-medium">超长上下文处理策略</h3>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {strategyOptions.map((option) => renderStrategyCard(option.value, option.title, option.description))}
              </div>
            </div>

            <div className="mt-6 space-y-2">
              <div className="flex items-center justify-between">
                <span className="font-medium">最大上下文 Token 预算</span>
                <span>{maxTokens}</span>
              </div>
              <Slider value={[maxTokens]} min={1000} max={32000} step={1000} onValueChange={(value) => setMaxTokens(value[0])} />
              <p className="text-sm text-muted-foreground">策略处理后的上下文需要尽量落在这个预算内。</p>
            </div>

            {strategyType === "SLIDING_WINDOW" && (
              <div className="mt-4 space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-medium">预留输出预算比例</span>
                  <span>{(reserveRatio * 100).toFixed(0)}%</span>
                </div>
                <Slider value={[reserveRatio]} min={0} max={0.6} step={0.05} onValueChange={(value) => setReserveRatio(value[0])} />
                <p className="text-sm text-muted-foreground">为输出和后续补充内容预留预算，比例越高，保留的历史越少。</p>
              </div>
            )}

            {strategyType === "SUMMARIZE" && (
              <>
                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">最近消息预算比例</span>
                    <span>{(reserveRatio * 100).toFixed(0)}%</span>
                  </div>
                  <Slider value={[reserveRatio]} min={0} max={0.8} step={0.05} onValueChange={(value) => setReserveRatio(value[0])} />
                  <p className="text-sm text-muted-foreground">
                    在摘要策略里，这个值表示给最近原始消息保留多少 token 预算，不是“保留消息条数”。
                  </p>
                </div>

                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">摘要触发阈值</span>
                    <span>{summaryThreshold}%</span>
                  </div>
                  <Slider value={[summaryThreshold]} min={50} max={95} step={5} onValueChange={(value) => setSummaryThreshold(value[0])} />
                  <p className="text-sm text-muted-foreground">
                    当历史 token 使用率达到这个百分比时触发摘要压缩。后端语义已经按 token 百分比处理。
                  </p>
                </div>
              </>
            )}

            {strategyType === "RELEVANCE_RECALL" && (
              <>
                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">最近消息预算比例</span>
                    <span>{(reserveRatio * 100).toFixed(0)}%</span>
                  </div>
                  <Slider value={[reserveRatio]} min={0.1} max={0.8} step={0.05} onValueChange={(value) => setReserveRatio(value[0])} />
                  <p className="text-sm text-muted-foreground">
                    固定保留最近原始消息的预算比例，其余预算用于从更早历史中召回相关内容。
                  </p>
                </div>

                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">召回触发阈值</span>
                    <span>{recallTriggerThreshold}%</span>
                  </div>
                  <Slider
                    value={[recallTriggerThreshold]}
                    min={50}
                    max={95}
                    step={5}
                    onValueChange={(value) => setRecallTriggerThreshold(value[0])}
                  />
                  <p className="text-sm text-muted-foreground">当历史 token 使用率达到该百分比时，开始执行相关性召回。</p>
                </div>

                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">召回组数量 Top K</span>
                    <span>{recallTopK}</span>
                  </div>
                  <Slider value={[recallTopK]} min={1} max={8} step={1} onValueChange={(value) => setRecallTopK(value[0])} />
                  <p className="text-sm text-muted-foreground">最终最多保留多少个相关历史分组进入上下文。</p>
                </div>

                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">最低相关度</span>
                    <span>{recallMinScore.toFixed(2)}</span>
                  </div>
                  <Slider value={[recallMinScore]} min={0} max={1} step={0.05} onValueChange={(value) => setRecallMinScore(value[0])} />
                  <p className="text-sm text-muted-foreground">过滤掉得分过低的历史候选，避免把无关内容带回上下文。</p>
                </div>

                <div className="mt-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="font-medium">最大候选数</span>
                    <span>{recallMaxCandidates}</span>
                  </div>
                  <Slider
                    value={[recallMaxCandidates]}
                    min={5}
                    max={40}
                    step={1}
                    onValueChange={(value) => setRecallMaxCandidates(value[0])}
                  />
                  <p className="text-sm text-muted-foreground">向量检索阶段最多保留多少个候选，再进入后续排序与裁剪。</p>
                </div>

                <label className="mt-4 flex items-start gap-3 rounded-lg border p-4">
                  <input
                    type="checkbox"
                    checked={enableRerank}
                    onChange={(event) => setEnableRerank(event.target.checked)}
                    className="mt-1 h-4 w-4"
                  />
                  <div>
                    <div className="font-medium">启用重排</div>
                    <p className="text-sm text-muted-foreground">对召回出的历史分组再做一次重排，通常能提升相关性，但会增加额外延迟。</p>
                  </div>
                </label>
              </>
            )}
          </TabsContent>
        </Tabs>

        <DialogFooter className="mt-4 border-t pt-4">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            取消
          </Button>
          <Button onClick={handleSave} disabled={!selectedModelId || saving || loading} className="gap-1">
            {saving ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin" />
                保存中...
              </>
            ) : (
              <>
                <CheckCircle className="h-4 w-4" />
                保存配置
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

$ErrorActionPreference = "Stop"

$apiBase = "http://localhost:8080/api/ehm/v1"

function Invoke-EhmJson {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [ValidateSet("GET", "POST", "PUT", "PATCH")][string]$Method = "GET",
        [object]$Body
    )

    $arguments = @{
        Uri         = $Uri
        Method      = $Method
        ContentType = "application/json; charset=utf-8"
    }
    if ($null -ne $Body) {
        $arguments.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    Invoke-RestMethod @arguments
}

$ready = Invoke-EhmJson -Uri "http://localhost:8080/health/ready"
if ($ready.status -ne "UP" -or $ready.mongo -ne "UP") {
    throw "后端或MongoDB未就绪。"
}

$summaryBefore = Invoke-EhmJson -Uri "$apiBase/dashboard/summary"
$devices = Invoke-EhmJson -Uri "$apiBase/devices"
$deviceCount = $devices.Count
if ($deviceCount -lt 8) {
    throw "设备初始化数据不足，预期至少8台，实际$deviceCount台。"
}

# 用原值更新一台设备，验证设备台账PUT链路，同时不改变演示业务含义。
$device = Invoke-EhmJson -Uri "$apiBase/devices/QC-02"
$deviceUpdate = [ordered]@{
    name            = $device.name
    type            = $device.type
    area            = $device.area
    condition       = $device.condition
    health          = $device.health
    risk            = $device.risk
    riskClass       = $device.riskClass
    quality         = $device.quality
    ready           = $device.ready
    alarm           = $device.alarm
    maintenanceDate = $device.maintenanceDate
    owner           = $device.owner
    temperature     = $device.temperature
    vibration       = $device.vibration
    current         = $device.current
    rulDays         = $device.rulDays
}
$updatedDevice = Invoke-EhmJson -Uri "$apiBase/devices/QC-02" -Method PUT -Body $deviceUpdate

$acknowledgedAlarm = Invoke-EhmJson -Uri "$apiBase/alarms/EHM-ALM-0902-001/acknowledge" -Method POST -Body @{
    operator = "Demo联调程序"
}

# 只创建一次联调工单，脚本可重复执行而不会不断制造重复数据。
$orders = Invoke-EhmJson -Uri "$apiBase/work-orders"
$verificationOrder = foreach ($order in $orders) {
    if ($order.title -eq "全栈Demo接口联调验证") {
        $order
        break
    }
}
if ($null -eq $verificationOrder) {
    $verificationOrder = Invoke-EhmJson -Uri "$apiBase/work-orders" -Method POST -Body @{
        deviceCode    = "QC-02"
        title         = "全栈Demo接口联调验证"
        priority      = "P3 低"
        assignee      = "实验室联调组"
        source        = "系统联调"
        description   = "验证MongoDB持久化、工单创建和状态推进链路。"
        plannedWindow = "Demo演示窗口"
    }
}
$advancedOrder = Invoke-EhmJson -Uri "$apiBase/work-orders/$($verificationOrder.orderNo)/status" -Method PATCH -Body @{
    status = "待执行"
}

$assistant = Invoke-EhmJson -Uri "$apiBase/assistant/chat" -Method POST -Body @{
    message = "GT-01目前有什么风险，建议怎么处理？"
}
$summaryAfter = Invoke-EhmJson -Uri "$apiBase/dashboard/summary"

[ordered]@{
    ready              = $ready.status
    mongo              = $ready.mongo
    deviceCount        = $summaryAfter.totalDevices
    devicePutVerified  = ($updatedDevice.code -eq "QC-02")
    alarmStatus        = $acknowledgedAlarm.status
    workOrderNo        = $advancedOrder.orderNo
    workOrderStatus    = $advancedOrder.status
    assistantMode      = $assistant.mode
    assistantHasAnswer = -not [string]::IsNullOrWhiteSpace($assistant.answer)
    activeOrdersBefore = $summaryBefore.activeWorkOrders
    activeOrdersAfter  = $summaryAfter.activeWorkOrders
} | ConvertTo-Json -Depth 5

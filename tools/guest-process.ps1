function Test-EndCraftGuestRunning {
    # The shared guest marker also sees elevated clients whose command line CIM cannot read.
    try {
        $taskMarker = [Threading.Mutex]::OpenExisting('Local\EndCraft_v1_minecraft')
        $taskMarker.Dispose()
        return $true
    } catch [UnauthorizedAccessException] {
        return $true
    } catch [Threading.WaitHandleCannotBeOpenedException] {
        return $false
    }
}

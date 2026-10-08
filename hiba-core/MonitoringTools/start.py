import datetime
import HardwareUsage, NetworkMonitor
from threading import Thread


def main():
    currentTime = datetime.datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    hardwareInfoFileName = "Device_" + currentTime + ".csv"
    networkInfoFileName = "Network_" + currentTime + ".csv"
    hardware = Thread(target = HardwareUsage.print_usage, args = (hardwareInfoFileName,))
    network = Thread(target = NetworkMonitor.print_network_bytes_per_second, args = (networkInfoFileName,))
    hardware.start()
    network.start()


if __name__ == "__main__":
    main()
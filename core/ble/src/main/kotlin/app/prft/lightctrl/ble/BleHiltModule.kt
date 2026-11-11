package app.prft.lightctrl.ble

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleHiltModule {

    @Binds
    @Singleton
    abstract fun bindBleAdapterGateway(impl: AndroidBleAdapterGateway): BleAdapterGateway
}
